# 設計メモ

実装上の判断、既知の制約、Spring Boot との対応、UI レンダラの比較です。
過去の調査資料は [archive/](archive/) に置いています。

## 決定事項

| 決定 | 理由 | 詳細 |
|---|---|---|
| 認証は JWT（アクセストークン）+ リフレッシュトークン | リクエストごとの DB 照合をなくし、失効は寿命で上限を切る | [usage.md](usage.md) |
| コネクションプールは 100 | 既定の 20〜50 では c=100 のスループットが頭打ちになる | [performance.md](performance.md) |
| EclipseLink の共有 L2 キャッシュを無効化 | 小さなテーブルではロック競合が DB より高くつく（Liberty で +82%） | [performance.md](performance.md) |
| UI は lit-html（vendor 同梱） | ビルド不要・CSP 安全・再描画が差分更新になる | 下の「UI レンダラの比較」 |
| Jakarta Data 1.0 は不採用 | Hibernate 依存と 4 基盤の実装差 | [archive/jakarta-data-assessment.md](archive/jakarta-data-assessment.md) |
| Quarkus の REST 層は Quarkus REST | RESTEasy Classic より 4.0 の本線で、起動とスループットに有利 | [performance.md](performance.md) |
| 月次スケジューラは Concurrency 3.1 | EJB を外して Liberty / WildFly の構成を軽くする（EE 11 の標準 API） | [runtimes.md](runtimes.md) |
| Liberty は feature 最小構成、WildFly は Galleon slim | 不要な feature / layer を外して起動時間とサイズを抑える | [runtimes.md](runtimes.md) |
| core は仕様別 API に依存（アンブレラ `jakartaee-api` を使わない） | EJB / Servlet / Quarkus が classpath に載らないので、移植性の違反がコンパイルエラーになる。`order-core` の enforcer が二重に防ぐ | 下の「モジュール構成と移植性」 |
| セキュリティヘッダの値は core の `SecurityHeaders` が定義 | 3 つの WAR アダプタが同じ値を参照し、Quarkus の設定値との一致をテストで強制する | 下の「セキュリティヘッダ」 |

実装上の判断と、Spring Boot との対応関係です。

- Jakarta Data の導入検討: [jakarta-data-assessment.md](archive/jakarta-data-assessment.md)
- Spring Boot との機能差の詳細: [spring-parity-assessment.md](archive/spring-parity-assessment.md)
- UI レンダラの比較: [ui-lit-comparison.md](#ui-レンダラの比較lit-html)

## 設計上の注意
### モジュール構成と移植性

```
order-api (生成DTO)  order-domain (Entity)
        \                  /
         order-core (web / service / repository / batch + UI + persistence.xml)
                |
   +------------+------------+-------------+
   |            |            |             |
order-quarkus  liberty     wildfly     glassfish
```

- `order-core` は層ごとに Maven モジュール分割しない。デプロイ単位が 1 つである以上、
  分割しても得られるのはビルドの複雑さだけ（`web → service → repository` はパッケージとレビューで守る）
- 移植性の担保は 2 段構え:
  1. 仕様別 API（`jakarta.ws.rs-api` / `jakarta.persistence-api` / `jakarta.batch-api` など、すべて `provided`）だけに依存する。
     `jakarta.ejb-api` や `jakarta.servlet-api` は classpath に無いので、うっかり EJB Timer を書くとコンパイルが通らない
  2. `order-core` の `maven-enforcer-plugin` が `io.quarkus:*` / `org.springframework*` / `org.hibernate.orm:*` /
     `jakarta.ejb:*` / `jakarta.servlet:*` を推移依存まで含めて禁止する
- 単体テストは `order-core/src/test/java`（コンテナも DB も不要、`mvn -pl order-core -am test` で数秒）。
  統合テスト（`@QuarkusTest`）は `order-quarkus/src/test/java` に置き、Embedded PostgreSQL を使う
- Liberty の JDBC ドライバは `provided` 依存 + `maven-dependency-plugin` で `src/main/liberty/config/lib/` に配置する。
  ドライバは WAR に入れない（サーバー側の責務）

### セキュリティヘッダ

CSP / `X-Content-Type-Options` / `Referrer-Policy` の**値**は `order-core` の
`com.example.order.core.web.SecurityHeaders` が定義します。core は UI（`META-INF/resources`）も持っているため、
CSP を UI と同じ場所で管理できます。

- Quarkus: Servlet コンテナが無く、静的ファイルにもヘッダを付けられる `quarkus.http.header.*` を使う。
  設定ファイルから Java 定数は参照できないため、`SecurityHeadersTest` が両者の一致を検証する（ずれたらテスト失敗）
- Liberty / WildFly / GlassFish: 各アダプタの `SecurityHeadersFilter` が定数をそのまま適用する
  （3 アダプタで値がドリフトしない）

### Quarkus の REST 層

Quarkus 3.40 では RESTEasy Classic ではなく、`quarkus-rest` / `quarkus-rest-jsonb`
（RESTEasy Reactive）を使っています。Classic と同一ホストで A/B したところ、
`/api/auth/status` は 141.8k → 160.4k、`/api/auth/me` は 121.4k → 132.4k、
`/api/products` はほぼ同等（14.3k → 13.9k）でした。Quarkus 4.0 では Classic の移行が必要になるため、
早い段階で本線の実装に寄せています。

Quarkus REST ではリクエストボディの JSON パース失敗が `JsonbException` として直接届きます
（Classic は `ProcessingException` でラップする）。`UnhandledExceptionMapper` は原因チェーンに
JSON 例外がある場合に 400 を返すようにして、どちらの実装でも同じ応答にしています。

### Quarkus と Jakarta Batch

Quarkus 本体には Jakarta Batch の実装が含まれていません。このサンプルでは [Quarkiverse JBeret](https://github.com/quarkiverse/quarkus-jberet)（`io.quarkiverse.jberet:quarkus-jberet:2.11.0`）を追加し、**アプリケーションコードは `jakarta.batch.*` だけ**を使っています。したがって、Liberty の `batch-2.1` に移ってもジョブ XML と Batch アーティファクトはそのまま動く想定です。

### EntityManager の注入

`order-core` の Repository 層は `@Inject EntityManager` を使います。

- Quarkus: Hibernate ORM の組み込み `EntityManager` Bean が満たす
- Liberty: JPA コンテナ（EclipseLink）が CDI の `EntityManager` Bean を提供する

どちらの実行基盤も `@Inject EntityManager` を解決できるため、Liberty 専用の Producer は不要でした。
（環境によっては `@PersistenceContext` が必要になることがあるため、その場合はアダプタ側で Producer を追加します。）

### Repository 層

Spring の `@Repository` に相当する層を `com.example.order.core.repository` に置いています。

- `OrderRepository` / `ProductRepository` / `CustomerRepository` / `InvoiceRepository`
- CDI の `@ApplicationScoped` Bean（Jakarta EE に `@Repository` という仕様アノテーションはない）
- `@Transactional` は Service 側に置き、Repository は現在のトランザクションに参加するだけ
- Spring Data JPA のようなインターフェースの自動実装はないため、JPQL を Repository に手書きしている
- テストや差し替えがしやすいよう、Service から `EntityManager` を排除

### ORM と DTO マッピング

- ORM は JPA 実装を使っています（Quarkus: Hibernate ORM、Liberty: EclipseLink）
- `order-domain` の Entity は JPA アノテーションのみ
- Web の DTO クラスは `order-api` の OpenAPI 定義から自動生成
- Entity → DTO の変換は Service 内で手書き（サンプルとして依存を増やさないため）
- 大規模化する場合は MapStruct などの compile-time mapper を `web` と `service` の間に置くのが選択肢

### `JobOperator` の取得

コアの `BatchService` は `Instance<JobOperator>` を注入し、未解決なら `BatchRuntime.getJobOperator()` にフォールバックします。

- Quarkus JBeret は `JobOperator` を CDI Bean として公開
- Liberty は標準 API の `BatchRuntime` から取得

### スケジューラと初期データ

スケジューラは実行基盤のライフサイクルに依存するため、コアには置かずアダプタに分離しています。

- Quarkus: JBeret cron
- Liberty / WildFly / GlassFish: Concurrency 3.1 `@Asynchronous(runAt = @Schedule(...))`

`@Schedule` は CDI の `Startup` イベントで有効化し、最初の実行は次の予定時刻（毎月 1 日 02:00）です。
スケジュールされたメソッドが例外を投げると再スケジュールが止まるため、ジョブ起動の失敗は
ログに記録して次の月までタイマーを維持します。

初期データ投入は CDI 4.1 の `Startup` イベント（`StartupDataSeeder`）で共通化しています。

### 永続化

`order-core/src/main/resources/META-INF/persistence.xml` を共通利用します。

- Quarkus は Hibernate ORM、Liberty は EclipseLink
- エンティティは JPA 標準アノテーションのみ
- スキーマ生成は標準の `jakarta.persistence.schema-generation.database.action=drop-and-create`（デモ専用）
  - Quarkus は persistence.xml があると `quarkus.hibernate-orm.*` を使わないため、ランタイム側へは移せません
  - 本番では `none` にして Flyway/Liquibase などに置き換えてください
- `hibernate.dialect` だけは Hibernate 用に明示しています。Quarkus は静的初期化時にメタデータを組み立てるため、
  JDBC 接続なしでは方言を自動検出できないためです
- DB は PostgreSQL 17（ローカルは Docker Compose または Embedded PostgreSQL、テストは Embedded PostgreSQL）

### Jakarta Data 1.0

現時点では導入していません。Jakarta Data 1.0 は stateless 専用で、現在の managed entity + JPQL の
Repository とは意味論が異なること、Hibernate の生成実装を `order-core` に置くと Liberty / GlassFish で
動かなくなることから、導入する場合の選択肢と判定基準を
[jakarta-data-assessment.md](archive/jakarta-data-assessment.md) にまとめています。

### Spring Boot との機能差

Spring Boot の AutoConfiguration / Starter / DevTools は Jakarta EE の仕様だけでは再現できませんが、
CDI Startup イベント、JPA 監査、CDI トランザクションイベント、CDI インターセプタ、MP Health などで
プログラミングモデルと運用機能は近づけられます。優先度と基盤ごとの差は
[spring-parity-assessment.md](archive/spring-parity-assessment.md) にまとめています。

### 既知の制約

- 起動のたびに `drop-and-create` でテーブルを作り直すため、オンラインの注文データは消えます（デモ用）
- 一覧 API は `PageResponse`（`content` / `page` / `size` / `totalElements` など）を返します。旧来の配列レスポンスとは互換性がありません
- CDI `Startup` イベントによる初期データ投入は 4 基盤で確認済みです。WildFly / GlassFish の CSV 取込は、サーバーの作業ディレクトリ配下に書き込み可能な `data` ディレクトリが必要です
- CSV 取込は再実行すると同じ注文を再度作成します（重複排除は未実装）
- 認証はアプリ発行の JWT（アクセストークン）+ opaque リフレッシュトークンです。Jakarta Security / OIDC ではありません
- アクセストークンはステートレスなので、発行済みトークンの即時失効はできません（寿命で上限を切っています）
- デモユーザー（`order.demo-users=true`）は固定パスワードです。本番では有効にせず、`order.admin-password` などで初期パスワードを渡したうえで、IdP / MFA / パスワードリセットを検討してください
- Cookie の `Secure` 属性は `order.cookie-secure`（`ORDER_COOKIE_SECURE`）で決めます。既定の `auto` は HTTPS のときだけ付与し、
  本番では `always` を推奨します。TLS 終端プロキシの背後では、プロキシが `X-Forwarded-Proto` を必ず上書きする構成にしたうえで
  `order.trust-forwarded-proto=true` を設定してください（未設定ならクライアントが送ったこのヘッダーは無視します）
- CORS は設定していません（画面は同一オリジン想定）。別オリジンの SPA から呼ぶ場合はアダプタ側で設定してください
- JMS、JTA の XA は含めていません
- Quarkus JBeret は Quarkiverse のコミュニティ拡張です。本番利用時はサポート状況を確認してください
- WildFly / GlassFish はコンテナ側の PostgreSQL datasource 設定が必要です。自動テストは Quarkus のみです
- Embedded PostgreSQL は非 root ユーザーで実行する必要があります（CI のコンテナで root の場合に注意）


## Spring Boot との対応（参考）
| Spring Boot | このサンプル |
|---|---|
| Spring MVC / `@RestController` | JAX-RS `@Path` / `@GET` / `@POST` |
| Spring Data JPA / Repository | JPA `EntityManager` + JPQL（`repository/*Repository`） |
| OpenAPI / DTO | `openapi-generator-maven-plugin`（contract-first） |
| Spring Batch | Jakarta Batch（Job XML + Chunk/Batchlet） |
| `@Scheduled` | Quarkus: JBeret cron / Liberty: Concurrency 3.1 `@Schedule` |
| `@Transactional` | `jakarta.transaction.Transactional` |
| Bean Validation | `jakarta.validation` |
| `ApplicationRunner` | CDI `Startup` イベント（`StartupDataSeeder`） |
| `@TransactionalEventListener` | CDI トランザクションイベント（`OrderAuditListener`） |
| `@Aspect` | CDI Interceptor（`@Logged`） |
| `@CreatedDate` / `@LastModifiedDate` | JPA `@PrePersist` / `@PreUpdate`（`Auditable`） |
| `Pageable` / `Page` | `PageQuery` / `PageResponse` レコード（`sort` 対応） |
| Spring Security / `@PreAuthorize` | JAX-RS Filter + `@RequireRole`（`RoleAuthorizationFilter`） |
| `ProblemDetail` | RFC 9457（`Accept: application/problem+json`） |
| fat jar | Quarkus fast-jar / Liberty WAR |

Jakarta / MicroProfile 標準でどこまで Spring Boot に近づけるかの詳細は
[spring-parity-assessment.md](archive/spring-parity-assessment.md) を参照してください。

## UI レンダラの比較（lit-html）
`order-core` の SPA（1 ページ + JSON API）を対象に、`innerHTML` ベースの手書きレンダリングを
[lit-html 3.3.3](https://www.npmjs.com/package/lit-html) に置き換えた結果の比較です。
ビルド工程は追加せず、ES モジュールをそのまま配信します。

### 結論

- **置き換えて正解**。`innerHTML` と `esc()` が 0 になり、同じデータの再描画で DOM 変更が 0 件になった
- 代償は **JS が gzip で +5.2 KB**（5.5 KB → 10.7 KB）と、vendor 4 ファイルの保守。
  ただし HTTP 圧縮を有効にしたため、実転送は旧実装（圧縮なし 34.7 KB）より小さくなった
- 行数はほぼ変わらない（502 → 505 行）。**行数ではなく「壊れにくさ」に効く変更**

### 変更内容

| ファイル | 変更 |
|---|---|
| `app.js` | `innerHTML` の文字列組み立てを lit のテンプレートに置換。`esc()` を全廃 |
| `index.html` | `<script type="module" src="app.js">`（モジュール化） |
| `vendor/lit-html/` | 3.3.3 の実行時ファイル 4 個 + LICENSE + README（BSD-3-Clause） |

`lit-html.js`（7.3 KB）+ `directive.js` + `directive-helpers.js` + `directives/repeat.js`。
`development/` ビルドや source map は同梱していません。

### 静的メトリクス

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

### 実行時メトリクス

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

### 機能パリティ（すべて OK）

14 項目をブラウザ自動操作で確認:

- ログイン / ログアウト（401 時のリダイレクト含む）
- 注文作成（明細 2 行、数量・商品の変更）→ トースト表示 → 一覧更新
- 注文の確定 → `CONFIRMED`、取消 → `CANCELLED`（在庫も更新）
- ページング（前へ / 次へ）、表示件数変更、ソート、ステータス絞り込み
- 商品タブ・請求書タブの表示とページング
- バッチ実行 → 実行状況ボックスの表示 → `COMPLETED` までポーリング → 実行履歴
- コンソールエラー 0、ページエラー 0

### XSS

顧客名に `<img src=x onerror="window.__xss=1"><b>bold</b>` を DB 経由で入れて表示:

- `window.__xss` は未設定
- 表の中に `img` / `b` 要素は 0
- セルには文字列としてそのまま表示

lit の補間は常にテキストとして扱うため、`esc()` の呼び忘れという事故が起きません。

### コストとリスク

| 項目 | 内容 |
|---|---|
| サイズ | JS +5.2 KB（gzip）。HTTP 圧縮により実転送は JS+CSS で 15.9 KB（旧実装は圧縮なし 34.7 KB） |
| 依存 | vendor 4 ファイル（BSD-3-Clause）。更新は `vendor/lit-html/README.md` の手順で手動コピー |
| 学習 | `html` テンプレートタグと `repeat`（キー必須）の理解が要る |
| 互換 | `app.js` が ES モジュールになる（`type="module"`）。古いブラウザは対象外 |
| サーバー | 変更なし。4 ランタイムとも `META-INF/resources` から配信されるだけ |

### 採用判断

- 「`innerHTML` + `esc()` をなくしたい」「再描画のチラつき・フォーカス喪失を減らしたい」なら採用
- 「JS の総量を増やしたくない」「依存を増やしたくない」なら、`<template>` + `cloneNode`（依存ゼロ）で
  同じ目的の 8 割は達成できる。ただし条件分岐の記述は冗長になる
- MPA 化とは独立の話。1 ページのままでも効果は出る（今回がその構成）

### 測定方法

- Playwright（Chromium）で操作し、`PerformanceObserver` / `MutationObserver` で計測
- 再描画の測定は「表示件数 20 で同じページを再読込」し、`#orders-body` の
  `addedNodes` / `removedNodes` を 1.5 秒間カウント
- ポーリングは請求書バッチを開始し、実行状況ボックスを 3 秒間観測
- 数値は 1 台のローカル環境での参考値。データ量（注文 44 件・商品 4 件）が小さい点に注意

## 過去の調査（archive）

- [archive/jakarta-data-assessment.md](archive/jakarta-data-assessment.md) — Jakarta Data 1.0 の導入検討
- [archive/spring-parity-assessment.md](archive/spring-parity-assessment.md) — Spring Boot との機能差の詳細
