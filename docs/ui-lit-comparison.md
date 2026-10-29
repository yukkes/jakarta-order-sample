# UI レンダラ比較: 素の DOM 操作 vs lit-html

`order-core` の SPA（1 ページ + JSON API）を対象に、`innerHTML` ベースの手書きレンダリングを
[lit-html 3.3.3](https://www.npmjs.com/package/lit-html) に置き換えた結果の比較です。
ビルド工程は追加せず、ES モジュールをそのまま配信します。

## 結論

- **置き換えて正解**。`innerHTML` と `esc()` が 0 になり、同じデータの再描画で DOM 変更が 0 件になった
- 代償は **JS が gzip で +5.2 KB**（5.5 KB → 10.7 KB）と、vendor 4 ファイルの保守。
  ただし HTTP 圧縮を有効にしたため、実転送は旧実装（圧縮なし 34.7 KB）より小さくなった
- 行数はほぼ変わらない（502 → 505 行）。**行数ではなく「壊れにくさ」に効く変更**

## 変更内容

| ファイル | 変更 |
|---|---|
| `app.js` | `innerHTML` の文字列組み立てを lit のテンプレートに置換。`esc()` を全廃 |
| `index.html` | `<script type="module" src="app.js">`（モジュール化） |
| `vendor/lit-html/` | 3.3.3 の実行時ファイル 4 個 + LICENSE + README（BSD-3-Clause） |

`lit-html.js`（7.3 KB）+ `directive.js` + `directive-helpers.js` + `directives/repeat.js`。
`development/` ビルドや source map は同梱していません。

## 静的メトリクス

| 指標 | before（素の DOM） | after（lit-html） |
|---|---|---|
| UI の JS ファイル数 | 1 | 1 + vendor 4 |
| `app.js` 行数 | 502 | 505 |
| `app.js` 生 / gzip | 20,241 B / 5,491 B | 19,598 B / 5,477 B |
| vendor 生 / gzip | — | 10,668 B / 5,180 B |
| **JS 合計 gzip**（ファイル別の合計） | **5,491 B** | **10,657 B** |
| `esc()` 呼び出し | 19 | 0 |
| `innerHTML` 代入 | 9 | 0 |

| 実転送（JS + CSS） | before | after |
|---|---|---|
| 圧縮なし（参考） | 34.7 KB | 45.9 KB |
| gzip 有効 | 8.9 KB（推定） | **15.9 KB（実測）** |

Quarkus は `quarkus.http.enable-compression=true` を設定済み（`order-quarkus/src/main/resources/application.properties`）。
`Content-Encoding: gzip` が付き、`app.js` は 19.6 KB → 5.8 KB、`index.css` は 13.9 KB → 3.8 KB で転送されます。
Servlet ランタイムで同じ効果を得るには、コンテナ側の圧縮設定（Liberty / WildFly / GlassFish の HTTP エンドポイント設定）が必要です。

## 実行時メトリクス

Chromium（headless, 1280×900）、ローカル Quarkus、同じ DB 初期データで測定。
転送量は gzip 有効後の値で、それ以外の時間は圧縮の有無でほとんど変わりません（この規模では転送が支配的でないため）。

| 指標 | before | after |
|---|---|---|
| ログイン → 注文一覧の行表示 | 309〜520 ms | 346 ms |
| タブ切替（商品）→ 表表示 | 37〜44 ms | 36〜38 ms |
| ソート変更 → 再描画（20 行） | 29〜41 ms | 27〜33 ms |
| **同一内容の再読込（20 行）: 追加/削除ノード** | **2 / 2** | **0 / 0** |
| **バッチ 1 秒ポーリング（3 秒）: 削除ノード** | **6** | **0** |
| コンソールエラー（CSP 違反含む） | 0 | 0 |

- ログイン時間は DB と JIT の影響が大きく、JS の差ではありません
- ソート・タブ切替は API 往復が支配的で、差は数 ms
- **差が出るのは再描画**: 同じ内容を描き直したとき、素の DOM 版は表とページャを丸ごと差し替え、
  lit 版は 1 ノードも変更しません。ポーリング中の削除ノードも 0

## 機能パリティ（すべて OK）

14 項目をブラウザ自動操作で確認:

- ログイン / ログアウト（401 時のリダイレクト含む）
- 注文作成（明細 2 行、数量・商品の変更）→ トースト表示 → 一覧更新
- 注文の確定 → `CONFIRMED`、取消 → `CANCELLED`（在庫も更新）
- ページング（前へ / 次へ）、表示件数変更、ソート、ステータス絞り込み
- 商品タブ・請求書タブの表示とページング
- バッチ実行 → 実行状況ボックスの表示 → `COMPLETED` までポーリング → 実行履歴
- コンソールエラー 0、ページエラー 0

## XSS

顧客名に `<img src=x onerror="window.__xss=1"><b>bold</b>` を DB 経由で入れて表示:

- `window.__xss` は未設定
- 表の中に `img` / `b` 要素は 0
- セルには文字列としてそのまま表示

lit の補間は常にテキストとして扱うため、`esc()` の呼び忘れという事故が起きません。

## コストとリスク

| 項目 | 内容 |
|---|---|
| サイズ | JS +5.2 KB（gzip）。HTTP 圧縮により実転送は JS+CSS で 15.9 KB（旧実装は圧縮なし 34.7 KB） |
| 依存 | vendor 4 ファイル（BSD-3-Clause）。更新は README の手順で手動コピー |
| 学習 | `html` テンプレートタグと `repeat`（キー必須）の理解が要る |
| 互換 | `app.js` が ES モジュールになる（`type="module"`）。古いブラウザは対象外 |
| サーバー | 変更なし。4 ランタイムとも `META-INF/resources` から配信されるだけ |

## 採用判断

- 「`innerHTML` + `esc()` をなくしたい」「再描画のチラつき・フォーカス喪失を減らしたい」なら採用
- 「JS の総量を増やしたくない」「依存を増やしたくない」なら、`<template>` + `cloneNode`（依存ゼロ）で
  同じ目的の 8 割は達成できる。ただし条件分岐の記述は冗長になる
- MPA 化とは独立の話。1 ページのままでも効果は出る（今回がその構成）

## 測定方法

- Playwright（Chromium）で操作し、`PerformanceObserver` / `MutationObserver` で計測
- 再描画の測定は「表示件数 20 で同じページを再読込」し、`#orders-body` の
  `addedNodes` / `removedNodes` を 1.5 秒間カウント
- ポーリングは請求書バッチを開始し、実行状況ボックスを 3 秒間観測
- 数値は 1 台のローカル環境での参考値。データ量（注文 44 件・商品 4 件）が小さい点に注意
