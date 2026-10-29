# セキュリティ強化の作業計画（完了・アーカイブ）

> **アーカイブ**: この計画の 15 項目はすべて完了しています（各見出しの ✅）。
> 現在の仕様は [../design.md](../design.md) と [../usage.md](../usage.md) を参照してください。


先日の Jakarta EE ベストプラクティス / セキュリティ調査で見つかった指摘を、すべて対応する計画。
各タスクは独立した PR にできる粒度に分けてあり、上から順に着手する。

## 全体像

| ID | 重要度 | タスク | 主な変更場所 |
|---|---|---|---|
| S1 | 高 | バッチ `inputFile` のパス・トラバーサルを塞ぐ | `OrderImportBatchlet`, `BatchService` |
| S2 | 高 | 初期アカウント `admin/admin` を既定で作らない | `SeedService`, Liberty `server.xml` |
| S3 | 高 | 無効化ユーザーのセッションを即時無効にする | `AuthService`, `AuthTokenRepository` |
| S4 | 中 | パスワードハッシュの強化（PBKDF2 600,000 回、形式の厳密化、再ハッシュ） | `PasswordHasher`, `User`, `AuthService` |
| S5 | 中 | ログイン試行回数の制限とロックアウト | `User`, `AuthService` |
| S6 | 中 | 認可を「拒否がデフォルト」にし、公開判定をアノテーションで行う | `web/auth/*` |
| S7 | 中 | `Secure` Cookie の判定を一本化する | `web/auth/*`, 新規 `SecuritySettings` |
| S8 | 中 | 想定外の例外を処理する `ExceptionMapper<Throwable>` | `web/error/*` |
| S9 | 中 | セキュリティヘッダーの付与と、CSP のためのインライン JS 撤去 | UI, 各ランタイム |
| S10 | 低 | 入力サイズの上限 | `order-api.yaml`, 各ランタイム |
| S11 | 低 | セッションのアイドルタイムアウトと同時セッション数の上限 | `AuthToken`, `AuthService` |
| S12 | 低 | 認証情報の外部化（DB パスワードなど） | 各ランタイム設定, `docs/runtimes.md` |
| S13 | 低 | Liberty の不要な権限・機能を削る | `server.xml` |
| S14 | 低 | core の `persistence.xml` の整理（`drop-and-create`、Hibernate 固有プロパティ） | `persistence.xml` |

実装順は S1 → S2 → S3（すぐ悪用できる穴）→ S4 → S5 → S6 → S7 → S8（認証まわりの土台）→ 残り。
S4 と S5 はどちらも `User` を変更するので、続けて行う。完了したタスクには ✅ を付ける。

## 共通: 設定値の読み方（`SecuritySettings`）

S2・S5・S7 で、ランタイムに依存しない設定値が必要になる。core では MicroProfile Config を使えないので、
`core/security/SecuritySettings`（`@ApplicationScoped`）を新しく作る。

- `System.getProperty(key)` → `System.getenv(KEY)` の順に読み、どちらもなければ既定値を使う
- キーは `order.xxx`（システムプロパティ）と `ORDER_XXX`（環境変数）の 2 系統
- 起動時に 1 回だけ読み、ログには値ではなく「設定されているかどうか」だけを出す
- Quarkus のテストでは、`order-quarkus/pom.xml` の surefire 設定の `systemPropertyVariables` で値を渡す

| キー | 既定値 | 使うタスク |
|---|---|---|
| `order.demo-users` | `false` | S2 |
| `order.admin-password` / `order.user-password` | なし | S2 |
| `order.cookie-secure` | `auto`（`always` / `auto` / `never`） | S7 |
| `order.trust-forwarded-proto` | `false` | S7 |
| `order.import-dir` | `data/import` | S1 |

---

## S1. バッチ `inputFile` のパス・トラバーサル（高） ✅

**問題**: `POST /api/batch/imports?inputFile=/etc/passwd` のように指定すると、`Path.of(inputFile)` を
そのまま読む。読めなかった行は内容ごと WARNING ログと `data/import-report.txt` に出るため、
ADMIN 権限があればサーバー上の任意ファイルを読み出せる。また、ファイルがないと classpath の CSV に
切り替わるので、ファイルの存在も外から判別できる。

**変更**
- `inputFile` で受け付けるのは**ファイル名だけ**にする。正規表現 `^[A-Za-z0-9._-]{1,100}\.csv$` に合わないものは拒否する
- ファイルは `SecuritySettings.importDir()`（既定は `data/import`）の下から読む
  - `base.resolve(name).normalize()` が `base` の配下にあることを確認する
  - さらに `toRealPath()` でも配下にあることを確認し、シンボリックリンクで外に出られないようにする
- 検証は 2 か所で行う
  - `BatchService.startImportJob`: 不正な名前やファイルがない場合は 400（`BusinessException.badRequest`）を返し、ジョブを開始しない
  - `OrderImportBatchlet.readLines`: 同じ検証をもう一度行う（多層防御）。不正ならジョブを FAILED にする
- classpath の CSV を使うのは `inputFile` が**空のときだけ**にする。指定されたファイルがない場合はエラーにし、存在の判別手段をなくす
- ログとレポートには**行の内容を書かない**。行番号とエラーの種類だけにする（例: `line 12: 商品が見つかりません`）
- UI（`index.html` の `import-file` 入力欄）の説明文を「`data/import/` に置いたファイル名」に変える
- `README.md:308-311` の例を `inputFile=orders.csv` に直し、`docs/runtimes.md` の「書き込み可能な `data/`」の説明に `data/import/` を追記する

**テスト**（`BatchJobTest`）
- `../etc/passwd`、`/etc/passwd`、`a/b.csv`、`x.txt` → 400
- 存在しない `missing.csv` → 400
- `data/import/` に置いた正しい CSV → COMPLETED
- `data/import/` の外を指すシンボリックリンク経由の読み取りが拒否される（OS がシンボリックリンクを作れる場合のみ）

## S2. 初期アカウント `admin/admin` を既定で作らない（高） ✅

**問題**: `app_users` が空なら、どの環境でも `admin/admin`・`user/user` が作られる。
Liberty の `basicRegistry` にも `admin/admin` がある。

**変更**
- `SeedService.seedUsers` の動作を次のように変える
  - `order.admin-password` が設定されていれば、そのパスワードで `admin` を作る（`user` も同様）
  - `order.demo-users=true` のときだけ、従来の `admin/admin`・`user/user` を作り、WARNING を 1 回出す
  - どちらでもなければユーザーを作らず、「ユーザーがいないためログインできません」という WARNING を出す
- デモ・開発用に既定値を設定する場所
  - Quarkus: core はシステムプロパティと環境変数しか読まない（`application.properties` は読まない）ので、
    surefire の `systemPropertyVariables` と `quarkus:dev` の `jvm.args` で `-Dorder.demo-users=true` を渡す
  - Liberty: `src/main/liberty/config/jvm.options` に `-Dorder.demo-users=true`（開発用であることをコメントで明記）
  - WildFly / GlassFish: `docs/runtimes.md` に、システムプロパティの設定手順を追記する
- README に設定表を追加し、`scripts/smoke-test.sh` はデモユーザーを有効にしたサーバーに対して実行するよう明記する
  （スクリプトはクライアント側なので、スクリプト自体は変更しない）
- Liberty の `basicRegistry` については S13 で扱う

**テスト**
- 既存のテスト（`login("user", "user")`）は `demo-users=true` で引き続き通ること
- `SecuritySettingsTest`（単体テスト）: 何も設定しなければユーザーを作らない、`demo-users=true` でデモ用パスワード、
  `admin-password` の指定がデモ設定より優先される
  （`@QuarkusTestProfile` では Quarkus の設定しか変えられず、core が読むシステムプロパティを切り替えられないため、単体テストにした）

## S3. 無効化ユーザーのセッションを即時無効にする（高） ✅

**問題**: `AuthService.authenticate` が `User.isEnabled()` を確認していないため、
無効化されたユーザーも、トークンの有効期限（最大 8 時間）まではアクセスできる。

**変更**
- `AuthTokenRepository.findByTokenHash` の JPQL に `and t.user.enabled = true` を追加する（DB 側で除外する）
- `AuthTokenRepository.deleteByUser(User)` を追加する。今後ユーザーの無効化やパスワード変更を実装するときは、必ずこれを呼ぶ
  - その旨を `User.setEnabled` の Javadoc に書く

**テスト**
- ログイン後に DB でユーザーを `enabled=false` にする → 次の API 呼び出しが 401 になる

## S4. パスワードハッシュの強化（PBKDF2 のまま・外部ライブラリなし）（中）✅

**問題**
- PBKDF2-HMAC-SHA256 × 210,000 回で、OWASP 推奨（600,000 回以上）を下回る
- パラメータを上げても既存ハッシュを移行する手段がない
- DB の salt / hash が壊れている、または `iterations <= 0` だと例外になり、ログインが 500 になる
- DB の `iterations` に上限がなく、異常値で CPU を使い続ける
- 存在しないユーザーではハッシュ計算をしないため、応答時間でユーザー名が判別できる

**方針の変更（Argon2id / Password4j は不採用）**
- 当初は Password4j で Argon2id にする予定だったが、検証で次のことが分かり取りやめた
  - `Argon2Function.getInstanceFromHash` は、ハッシュ文字列を解析しただけで `m` の分のメモリを確保し、
    そのインスタンスを static な Map に保持し続ける（`m=4194304` の文字列で OOM になることを確認）
  - `check()` はハッシュ文字列のパラメータではなくインスタンス自身のパラメータで検証するため、
    パラメータを変えると既存ハッシュが一致しなくなる
- そもそも Argon2 は、ログイン 1 回ごとに数十 MiB を確保するアルゴリズムである。
  OOM のリスクを持ち込まないため、メモリ使用量が一定の **JDK 標準 PBKDF2** を続ける。
  AGENTS.md の「core は Jakarta EE API のみ」の方針もそのまま守れる

**変更**
- `PasswordHasher`
  - PBKDF2-HMAC-SHA256、600,000 回、salt 16 byte、出力 32 byte
  - 保存形式は `$pbkdf2-sha256$i=<回数>$<salt>$<hash>`（パディングなしの Base64）。salt と回数をハッシュ文字列に含める
  - `matches(password, stored)`: 正規表現で厳密に解析する（salt 22 文字・hash 43 文字固定）。
    回数が 100,000〜2,000,000 の範囲外なら、計算せずに false を返す。例外は投げない
  - `needsRehash(stored)`: 回数が現行のポリシーと違えば true
  - `matchesDummy(password)`: ユーザーが存在しないとき用。`@PostConstruct` で作ったダミーのハッシュと照合し、応答時間を揃える
- `User`: `salt`・`iterations` カラムを削除し、`password_hash`（255）に上記の文字列を格納する。`changePassword(String)` を追加
  （DB は `drop-and-create` なので移行は不要）
- `AuthService.login`
  - ユーザーが存在しない、または無効化されている → `matchesDummy` を呼んでから 401
  - パスワードが一致して `needsRehash` が true なら、新しいポリシーで再ハッシュして保存する

**テスト**: `PasswordHasherTest`（形式、旧回数の検証と再ハッシュ判定、壊れた文字列、範囲外の回数を計算せずに拒否）、
`AuthTest`（210,000 回のハッシュがログイン時に 600,000 回へ更新される、存在しないユーザーとパスワード誤りが同じ 401）

## S5. ログイン試行回数の制限とロックアウト（中）✅

**問題**: 試行回数の制限がなく、ブルートフォースし放題。

**方針**: アカウント単位で失敗回数を DB に記録する。クラスタ構成でも共有でき、インメモリの状態を持たずに済む。
IP 単位の制限は、プロキシの背後では IP が信頼できないため、このサンプルではリバースプロキシや WAF に任せる。

**変更**
- `User` に `failedLoginCount`（int）と `lockedUntil`（日時）を追加する
- `AuthService.login` の処理
  - `lockedUntil` が未来なら、パスワードを照合せずに `matchesDummy` を呼び、**通常と同じ 401** を返す（ロック状態を外から判別できないようにする）
  - パスワード誤り → `failedLoginCount++`。5 回に達したら `lockedUntil = now + 15 分` にしてカウントを 0 に戻す
  - 成功 → `failedLoginCount = 0`、`lockedUntil = null`
- **トランザクションの注意**: `AuthException` は `RuntimeException` なので、そのまま投げると失敗回数の更新もロールバックされる。
  `@Transactional(dontRollbackOn = AuthException.class)` を付ける
- 閾値（5 回 / 15 分）は `AuthService` の定数にする
- 同時に失敗したときに加算が失われないよう、ログイン時は `UserRepository.findByUsernameForUpdate`
  （`PESSIMISTIC_WRITE`）でユーザーの行をロックする（`User` には `@Version` がない）
- 他人のアカウントを故意にロックさせる DoS が可能になるトレードオフがある。ロック時間を短め（15 分）にして影響を抑え、そのことを README に書く

**テスト**
- 5 回失敗したあとは、正しいパスワードでも 401
- `lockedUntil` を過去の日時に書き換えるとログインできる
- 成功するとカウンタが 0 に戻る

## S6. 認可を「拒否がデフォルト」にする（中）✅

**問題**
- `RoleAuthorizationFilter` は、`@RequireRole` がないリソースをログイン済みユーザー全員に許可する。付け忘れがそのまま穴になる
- `AuthFilter` は URI 文字列で公開パスを判定していて、パスの表記ゆれに弱い

**変更**
- `core/web/auth` に 2 つのアノテーションを追加する
  - `@PublicEndpoint`: 認証不要（`login`、`session`）
  - `@OptionalAuth`: トークンがあれば認証し、なくても通す（`status`）
  - ログイン済みなら誰でもよいエンドポイント（`me`、`logout`）は `@RequireRole({"USER", "ADMIN"})` にする
- `AuthFilter`（post-matching フィルタなので `ResourceInfo` が使える）
  - URI 文字列による判定（`normalize`、`indexOf("/api/")`）を削除し、`ResourceInfo` のメソッドとクラスのアノテーションで判定する
  - `OPTIONS` で `getResourceMethod() == null` の場合（自動生成される OPTIONS 応答）は通す。それ以外で `null` なら 401（fail closed）
- `RoleAuthorizationFilter`
  - `@RequireRole` も `@PublicEndpoint` / `@OptionalAuth` もない場合は 403 にする（拒否がデフォルト）
- AGENTS.md の「Public auth paths」の記述を「`@PublicEndpoint` / `@OptionalAuth` を付けたものだけ公開」に書き換える

**テスト**
- テスト用ソース（`order-quarkus/src/test`）にアノテーションのないリソースを置き、ログイン済みでも 403 になること
- `login`・`session`・`status` が未認証で呼べること、`me`・`logout` が未認証では 401 になること（既存テストの回帰確認）

## S7. `Secure` Cookie の判定を一本化する（中）✅

**問題**: `AuthResource.isSecure()` はリクエストのスキームだけを見て、`AuthFilter.isSecure()` は
`X-Forwarded-Proto` を無条件に信用しており、判定が食い違っている。TLS 終端プロキシの背後では
Cookie に `Secure` 属性が付かない。

**変更**
- `SecuritySettings.isSecureRequest(UriInfo, HttpHeaders)` を作り、両方からこれを呼ぶ
  - `order.trust-forwarded-proto=true` のときだけ `X-Forwarded-Proto` を見る
- Cookie の `Secure` 属性は `order.cookie-secure` で決める
  - `always`: 常に付ける（本番の推奨値）
  - `auto`: 上の判定結果に従う（既定値）
  - `never`: 付けない（HTTP でのローカル開発用）
- 本番では `always` を設定するよう README に書く

**テスト**
- `X-Forwarded-Proto: https` を付けても、`trust-forwarded-proto=false` なら `Secure` が付かない
- `cookie-secure=always` なら HTTP でも `Secure` が付く

## S8. 想定外の例外を処理する `ExceptionMapper<Throwable>`（中）✅

**問題**: `BusinessException`・`AuthException`・`ConstraintViolationException` 以外の例外は
ランタイムの既定のエラーページで返され、スタックトレースやサーバーのバージョンが表示されることがある。

**変更**
- `web/error/UnhandledExceptionMapper implements ExceptionMapper<Throwable>` を追加する
  - `WebApplicationException` の場合は、元のステータス（404 / 405 / 415 など）のまま、`ErrorResponse` か RFC 9457 の形式で返す
  - それ以外は 500 を返す。ランダムなエラー ID（UUID）を発行してサーバーログに例外ごと記録し、レスポンスには ID だけを載せる
  - 例: `message: "内部エラーが発生しました (errorId=…)"`
- 既存の専用マッパーのほうが型として具体的なので、JAX-RS の仕様上そちらが優先される。動作は変わらない
- **実装時に判明**: マッパーを入れると、不正な JSON（RESTEasy の `ProcessingException`）が 400 から 500 に変わってしまった。
  原因をたどって `JsonbException` / `JsonException` があれば 400 にすることで、従来の挙動を保った
- `@Produces` に `application/problem+json` が含まれていない場合に 406 にならないかを確認する（AGENTS.md の Gotchas）

**テスト**
- 存在しないパスで 404 が JSON で返る。`Accept: application/problem+json` なら RFC 9457 の形式
- 意図的に例外を投げるテスト用リソースで 500 になり、レスポンスに例外のクラス名やスタックトレースが含まれない

## S9. セキュリティヘッダーとインライン JS の撤去（中）✅

**問題**: CSP・`X-Content-Type-Options`・`frame-ancestors`・`Referrer-Policy` がない。
`index.html` にはインラインの `<script>` と 11 個の `onclick` などのインラインハンドラ、8 個の `style="..."` があり、
このままでは厳格な CSP をかけられない。

**変更**
1. UI
   - `index.html` の `<script>` を `app.js` に、`login.html` の `<script>` を `login.js` に切り出す
   - `onclick="confirmOrder(${id})"` などは `data-action="confirm" data-id="…"` にし、イベント委譲（親要素での `addEventListener`）で処理する
   - `style="..."` はユーティリティクラスに置き換える（`exec-box` の表示切り替えは `hidden` 属性を使う）
   - 浮動ラベルとフォーカスのスタイルは AGENTS.md のとおり CSS だけで実現したまま、JS は追加しない
2. ヘッダーの付与（静的ファイルにも付ける必要があるため、JAX-RS のフィルタではなくランタイム側で行う）
   - 付けるヘッダー
     ```
     Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
                              img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'
     X-Content-Type-Options: nosniff
     Referrer-Policy: no-referrer
     ```
     `<style>` ブロックが残るので、`style-src` は当面 `'unsafe-inline'` を許可する。CSS を外部ファイルにしたら外す
   - Quarkus（`quarkus-resteasy` は Vert.x 上で動き、Servlet がない）: `application.properties` の `quarkus.http.header."…".value` で設定する
   - Liberty / WildFly / GlassFish: Servlet の `jakarta.servlet.Filter`（`@WebFilter("/*")`）で付ける
     - Servlet API は Jakarta EE API に含まれるが、Quarkus には Servlet がないため、core ではなく各ランタイムのモジュールに置く
     - 3 つのモジュールに同じコードが並ぶ。共通化はしない（core に置くと Quarkus で読まれず混乱するため）
   - HSTS は TLS 終端（リバースプロキシ）で付けることを `docs/runtimes.md` に書く
3. AGENTS.md に「UI にインラインスクリプトやインラインハンドラを書かない（CSP のため）」を追加する

**テスト**
- Quarkus のテストで `/`・`/index.html`・`/api/products` のレスポンスに上記のヘッダーが付く
- 手動確認: 各ランタイムでブラウザの開発者ツールを開き、CSP 違反が出ないこと、注文の確定・取消・バッチ操作ができること

**実装メモ**
- `<script>`/`<style>` の切り出し（`app.js` / `login.js` / `index.css` / `login.css`）は完了済みだったので、
  残っていたインライン `onclick` 12 か所と `style="..."` 4 か所を `data-action` + イベント委譲と
  ユーティリティクラスに置き換えた。`exec-box` は `hidden` 属性、バッチタブは `hidden` で出し分ける
- `style-src` は `'unsafe-inline'` を外して `'self'` にした（CSS を外部ファイル化できたため）
- 同じ理由で `login.html` の「デモ用アカウント admin / admin」の表示を削除した（S2 の既定では作成されないため）
- 検証用に `SecurityHeadersTest` を追加（ヘッダー 3 種と、配信 HTML にインライン script/style/onclick が
  含まれないこと）。ブラウザでの手動確認は未実施

## S10. 入力サイズの上限（低）✅

**変更**
- `order-api.yaml`
  - `LoginRequest.username`: `maxLength: 50`（`User.username` のカラム長に合わせる）
  - `LoginRequest.password`: `maxLength: 128`
  - `CreateOrderRequest.customerCode`、`CreateOrderLine.productCode`: カラム長に合わせた `maxLength`
  - `CreateOrderRequest.lines`: `maxItems: 100`
  - `CreateOrderLine.quantity`: `maximum: 10000`（合計金額の桁あふれ防止）
  - 生成された DTO は手で編集しない（`target/generated-sources` には触らない）
- リクエストボディのサイズ上限
  - Quarkus: `quarkus.http.limits.max-body-size=1M`
  - その他のランタイム: 既定値を確認し、`docs/runtimes.md` に記載する

**テスト**: 129 文字のパスワード、101 行の注文、数量 10001 → いずれも 400

**実装メモ**
- `x-field-extra-annotation: '@jakarta.validation.Valid'` を `lines` に足し、フィールド側でも
  cascade するようにした（コンテナ要素の `@Valid` だけに頼らない）
- **Quarkus では `order-api` の Jandex インデックスが必要だった**。未登録だと Hibernate Validator が
  `CreateOrderLine` の制約を認識せず、`quantity: 10001` がそのまま通った。`order-api/pom.xml` に
  `jandex-maven-plugin` を足して JAR に `META-INF/jandex.idx` を同梱する方式にした（アプリ側の設定が不要になり、
  `order-api` を消費するどの Quarkus アプリでも効く）。`quarkus.index-dependency.order-api.*` でも動くことは確認済み

## S11. セッションのアイドルタイムアウトと同時セッション数の上限（低）✅

**変更**
- `AuthToken` に `lastUsedAt` を追加する
- `AuthService.authenticate` の処理
  - `lastUsedAt` から 30 分以上たっていれば失効させる（絶対期限 8 時間はそのまま）
  - `lastUsedAt` の更新は前回から 1 分以上たっている場合だけ行い、毎リクエストの UPDATE を避ける
- ログイン時に、そのユーザーの有効なトークンが 10 件を超えていれば古い順に削除する
- `LoginResponse.expiresAt` の意味（絶対期限）は変えない

**テスト**: `lastUsedAt` を 31 分前に書き換えると 401。11 回ログインすると最初のトークンが失効している
## S12. 認証情報の外部化（低）✅

**変更**: 開発用の既定値は残し、環境変数で上書きできるようにする
- Quarkus: `quarkus.datasource.password=${DB_PASSWORD:orders}`（ユーザー名と URL も同様）
- Liberty: `server.xml` で `<variable name="DB_PASSWORD" defaultValue="orders"/>` を定義し、`password="${DB_PASSWORD}"` で参照する
- WildFly / GlassFish: `docs/runtimes.md` の CLI 例を、パスワードを環境変数から渡す形に書き換える
  （WildFly は `${env.DB_PASSWORD}` 式、GlassFish は `asadmin` の `--passwordfile` やエイリアス）
- `docker-compose.yml`: `POSTGRES_PASSWORD: ${DB_PASSWORD:-orders}`

## S13. Liberty の不要な権限・機能を削る（低）✅

**変更**（どれも、削ったうえで動作確認してから確定する）
- `localConnector-1.0`: アプリからもドキュメントからも使われていなければ削除する
- `batchSubmitter` / `batchMonitor` の `EVERYONE`
  - アプリは未認証の主体として `JobOperator` を呼ぶため、`EVERYONE` が必要な可能性が高い
  - `batchManagement`（バッチの REST API）は有効になっていないので外部からは触れない。削除できるかを確認し、できなければ理由をコメントに残す
- `basicRegistry` の `admin/admin`
  - `batchAdmin` ロールのためにだけ存在している。不要なら `basicRegistry` ごと削除する
  - 必要な場合は、パスワードを `${env.LIBERTY_ADMIN_PASSWORD}` で外部化する

**確認**: Liberty でバッチの起動・一覧・再実行（restart）ができること

**実装メモ（Open Liberty 26.0.0.10 で確認）**
- `localConnector-1.0` を削除（アプリ・ドキュメントとも未使用）
- `basicRegistry` の `admin/admin` と `batchAdmin` ロールを削除
- `authorization-roles` の `batchSubmitter` / `batchMonitor`（`EVERYONE`）は**必要だった**。
  削ると `CWWKY0304W: User UNAUTHENTICATED is not authorized to start batch jobs` で 500 になる
- `basicRegistry` 自体は空（ユーザーなし）で残す。完全に削除すると ORB が
  `CWWKS9660E`（ユーザーレジストリが無い）を出すため
- 確認内容: 請求書バッチの起動（202）・一覧（COMPLETED）・再実行、CSV 取込（COMPLETED）、
  `USER` トークンで `/api/batch/executions` が 403

## S14. core の `persistence.xml` の整理（低）✅

**問題**
- `drop-and-create` が core に書かれているため、本番で起動するとデータが消える
- `hibernate.*` プロパティが core にあり、AGENTS.md の「Hibernate 固有のものを core に置かない」という方針と矛盾する

**変更**
- `hibernate.show_sql` / `hibernate.format_sql` は既定値と同じなので削除する
- `hibernate.dialect` は Hibernate 6 以降、自動検出されるので削除を試す（Quarkus のテストが通れば確定）
- `drop-and-create` をランタイム側に移せるかを調査する。ここは**調査タスク**とする
  - Quarkus は `persistence.xml` がある場合、`quarkus.hibernate-orm.*` の設定と併用できない
  - Liberty は `<jpa><defaultProperties>` で上書きできる見込み
  - WildFly / GlassFish の上書き方法は未確認
  - 移せない場合は、`persistence.xml` と README に「デモ専用。本番では `none` / `validate` にすること」と明記する

**実装メモ**
- `hibernate.show_sql` / `hibernate.format_sql` は削除した
- **`hibernate.dialect` は削除できなかった**。Quarkus は persistence.xml のメタデータを静的初期化時に組み立てるため、
  JDBC 接続がまだ無く、方言を自動検出できない（`Unable to determine Dialect without JDBC metadata` で起動失敗）。
  理由をコメントに残して残した
- `drop-and-create` はランタイム側へ移せないと判断した
  - Quarkus は persistence.xml があると `quarkus.hibernate-orm.*` を無視する
  - WildFly / GlassFish には persistence-unit のプロパティを外から上書きする手段がない
  - Liberty だけは `<jpa><defaultProperties>` で上書きできるが、4 基盤で挙動が揃わないので採用しない
  - そのため `persistence.xml` にデモ専用であることと本番での切り替え方（`none` / `validate` + Flyway など）を明記した

---

## 見送る項目（理由）

- **Jakarta Security（`HttpAuthenticationMechanism`）の採用**: GlassFish ではコンテナ認証が JAX-RS より先に割り込み、
  4 つのランタイムで同じ動作にならない（AGENTS.md の `@RequireRole` の注記と同じ理由）。独自の JAX-RS フィルタ方式を続ける
- **読み取り専用メソッドの `@Transactional(SUPPORTS)` 化**: JTA のコンテナ管理 EntityManager では、
  トランザクションの外だと遅延ロードが失敗するおそれがある。性能上の利点も小さい
- **コンストラクタインジェクションへの移行**: `@ApplicationScoped` の Bean にはプロキシ用に引数なしコンストラクタが必要で、
  コードがかえって冗長になる
- **`LocalDateTime` から `Instant` への移行**: API の `expiresAt` などの型にも影響するため、
  セキュリティ対応とは別の改善として扱う

## 共通の検証手順（各 PR）

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -pl order-quarkus -am test          # 必須
mvn -DskipTests package                 # 全ランタイムのビルド
```

- Liberty / WildFly / GlassFish を `order.demo-users=true` で起動し、`scripts/smoke-test.sh` を実行する
- UI に関わる変更（S1・S9）は、各ランタイムでブラウザから手動確認する
- テストは root 以外のユーザーで実行する。`target/` やドライバの JAR はコミットしない
