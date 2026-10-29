# 使い方: 認証・API・バッチ・テスト

このサンプルの API の使い方、認証、バッチ実行、OpenAPI 連携、テスト方法をまとめています。
起動手順は [runtimes.md](runtimes.md)、性能は [performance.md](performance.md) を参照してください。

## ログイン / 認証
- 初期ユーザーは `app_users` が空のときだけ作成し、パスワードはシステムプロパティか環境変数で渡します

| 設定（システムプロパティ / 環境変数） | 内容 |
|---|---|
| `order.admin-password` / `ORDER_ADMIN_PASSWORD` | `admin`（`ADMIN`）をこのパスワードで作成 |
| `order.user-password` / `ORDER_USER_PASSWORD` | `user`（`USER`）をこのパスワードで作成 |
| `order.demo-users=true` / `ORDER_DEMO_USERS=true` | パスワード未指定のユーザーを `admin` / `admin`、`user` / `user` で作成（ローカル専用） |
| `order.token-secret` / `ORDER_TOKEN_SECRET` | アクセストークンの署名鍵。未設定なら起動ごとにランダム生成（再起動でトークンが無効になる） |
| `order.access-token-ttl-seconds` / `ORDER_ACCESS_TOKEN_TTL_SECONDS` | アクセストークンの寿命（既定 600 秒） |

  DB の接続情報も環境変数で上書きできます（既定値はローカル開発用）。
  Quarkus は `application.properties` の `${DB_USER:orders}` 形式、Liberty は `server.xml` の `<variable>` で参照します。
  WildFly / GlassFish の設定手順は [runtimes.md](runtimes.md) を参照してください。

| 環境変数 | 既定値 | 内容 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | JDBC URL |
| `DB_USER` | `orders` | DB ユーザー |
| `DB_PASSWORD` | `orders` | DB パスワード（`docker compose` も同じ変数を使います） |

  どれも設定しないとユーザーは作成されず、ログインできません（起動ログに WARNING が出ます）。
  `quarkus:dev`、Quarkus のテスト、Liberty（`src/main/liberty/config/jvm.options`）では `order.demo-users=true` を設定済みです。
  `scripts/smoke-test.sh` は `user` / `user` でログインするため、デモユーザーを有効にしたサーバーに対して実行してください。
- 認証は 2 種類のトークンで行います
  - **アクセストークン**: HS256 署名の JWT（既定 10 分）。`ORDER_SESSION` Cookie または `Authorization: Bearer` で送り、
    検証は署名とクレームだけで、**DB アクセスはありません**
  - **リフレッシュトークン**: ランダムな不透明トークン。ハッシュだけを DB に保存します。`ORDER_REFRESH`（`HttpOnly`、`/api/auth`）
    Cookie、または `/api/auth/login` のレスポンスで受け取り、`POST /api/auth/refresh` で更新します（使うたびにローテーション）
- ブラウザは `/api/auth/session` で `ORDER_SESSION` / `XSRF-TOKEN` / `ORDER_REFRESH` の 3 つの Cookie を受け取ります
- アクセストークンが切れると SPA は自動で `/api/auth/refresh` を呼び、画面を維持します
- SPA の初期化は公開の `/api/auth/status` で認証状態を確認します
- 直接 API は `/api/auth/login` でアクセストークン（とリフレッシュトークン）を取得します
- 注文・商品・顧客・請求書は `USER` / `ADMIN`、バッチは `ADMIN` のみ利用できます
- 認可は「拒否がデフォルト」です。リソースメソッド（またはクラス）に `@RequireRole`・`@PublicEndpoint`・`@OptionalAuth`
  のどれも付いていなければ 403 になります。公開しているのは `login`・`session`（`@PublicEndpoint`）と `status`（`@OptionalAuth`）だけです
- Cookie 認証の POST / PUT / PATCH / DELETE には `X-XSRF-TOKEN` ヘッダーが必要です。Bearer 認証では不要です
- パスワードは PBKDF2-HMAC-SHA256（600,000 回、JDK 標準）で `$pbkdf2-sha256$i=…$<salt>$<hash>` 形式で保存します。
  反復回数を変えると、次回ログイン時に新しい回数で再ハッシュされます
- 同じアカウントで 5 回続けてログインに失敗すると、15 分間ロックします。ロック中・存在しないユーザー・パスワード誤りは
  すべて同じ 401 を返します。第三者がわざと失敗して他人をロックできるため、ロック時間は短めにしています。
  IP 単位の制限はリバースプロキシや WAF で行ってください
- セッション（リフレッシュトークン）は絶対期限 8 時間に加えて、30 分間更新がないと失効します。
  同時に保持できるのは 1 ユーザー 10 セッションまでで、11 個目にログインすると最も古いセッションが失効します
- **失効の反映はアクセストークンの寿命（既定 10 分）まで遅れます**。無効化・ログアウト・パスワード変更は
  リフレッシュの時点で即座に拒否され、以降新しいアクセストークンは発行されません
- 認可は JAX-RS Filter + `@RequireRole` で行います。GlassFish で `@RolesAllowed` を JAX-RS リソースに付けると
  コンテナ認証が起動し、`/api/auth/login` まで 401 になるためです

```bash
# 直接 API 用の Bearer トークン
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user","password":"user"}' | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/orders?page=0&size=10&sort=id,desc'

# ブラウザ用セッション（Cookie が返る）
curl -i -c cookies.txt -X POST http://localhost:8080/api/auth/session \
  -H 'Content-Type: application/json' \
  -d '{"username":"user","password":"user"}'
```

- 401: 未認証 / トークン無効・期限切れ
- 403: ロール不足 / CSRF トークン不正
- `Accept: application/problem+json` を指定すると、認証エラーも RFC 9457 形式になります


## オンライン API の例
```bash
# 商品・顧客（ページング）
curl -s 'http://localhost:8080/api/products?page=0&size=2'
curl -s 'http://localhost:8080/api/customers?page=0&size=2'

# 注文作成（DRAFT）
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{
        "customerCode": "C001",
        "lines": [
          {"productCode": "P002", "quantity": 2},
          {"productCode": "P004", "quantity": 1}
        ]
      }'

# 在庫引当して確定（CONFIRMED）
curl -s -X POST http://localhost:8080/api/orders/5/confirm

# キャンセル（確定済みなら在庫を戻す）
curl -s -X POST http://localhost:8080/api/orders/5/cancel
```

一覧 API（`/api/orders`、`/api/products`、`/api/customers`、`/api/invoices`）は
`PageQuery` / `PageResponse` レコードでページングします。

```bash
curl -s 'http://localhost:8080/api/orders?page=0&size=10'
```

```json
{
  "content": [],
  "page": 0,
  "size": 10,
  "totalElements": 4,
  "totalPages": 1,
  "first": true,
  "last": true,
  "hasNext": false,
  "hasPrevious": false
}
```

`page` は 0 始まり、`size` は 1〜100 です。範囲外の値はクランプされ、省略時は `page=0` / `size=20` になります。

`sort=field,asc|desc` でソートできます。複数項目を指定する場合は `sort` を繰り返します。

```bash
curl -s 'http://localhost:8080/api/products?page=0&size=2&sort=stock,desc'
curl -s 'http://localhost:8080/api/orders?sort=status,asc&sort=id,desc'
```

ソート項目はエンドポイントごとにホワイトリスト化されています。

- 注文: `id` / `orderNo` / `orderDate` / `status` / `totalAmount` / `createdAt` / `updatedAt`
- 商品: `code` / `name` / `unitPrice` / `stock`
- 顧客: `code` / `name`
- 請求書: `id` / `invoiceNo` / `billingMonth` / `totalAmount` / `taxAmount` / `createdAt` / `updatedAt`

許可されていない項目や不正な方向を指定した場合は 400 を返します。

エラーは既定で `{"status":409,"error":"BUSINESS_ERROR","message":"..."}` の形で返します。
`Accept: application/problem+json` を指定すると RFC 9457 の Problem Details を返します。
`@Valid` による入力チェックは 400、存在しない注文・顧客は 404、状態遷移や在庫不足は 409 です。
不正な JSON は 400、存在しないパスは 404、許可されていないメソッドは 405 で、どれも同じ JSON 形式です。
想定外の例外は `UnhandledExceptionMapper` が `{"error":"INTERNAL_ERROR","message":"内部エラーが発生しました (errorId=…)"}` の
500 にし、例外の詳細は同じ `errorId` でサーバーログにだけ出します（スタックトレースやサーバーのバージョンは返しません）。


## バッチ
### 1. 請求書生成（チャンク）

`invoiceJob` は対象月の注文を **5 件ずつ**読み、CONFIRMED の注文だけ請求書にします。

- `checkpoint-policy="item"` + `item-count="5"` で再実行可能
- `skip-limit="5"` と `InvalidOrderException` の skip 設定
- `InvoiceItemReader` は offset を checkpoint として返す
- `InvoiceItemWriter` は `invoiceNo` の重複をチェックして冪等に近づけている
- 確定済み注文は `INVOICED` に更新
- ジョブ/ステップリスナー（`BatchLogListener`）で開始・終了をログ出力

```bash
# 前月分を手動実行
curl -s -X POST 'http://localhost:8080/api/batch/invoices?month=2026-09'

# 実行状態（step の read/write/commit/skip も返す）
curl -s http://localhost:8080/api/batch/executions/1

# 請求書一覧
curl -s 'http://localhost:8080/api/invoices?month=2026-09'
```

月を省略すると前月になります。Quarkus では毎月 1 日 02:00 に自動実行する cron を設定済みです（`application.properties`）。

### 2. CSV 取込（バッチレット）

`orderImportJob` は `data/orders-import.csv`（classpath）を読み、1 行ずつ注文を作成します。

```bash
curl -s -X POST http://localhost:8080/api/batch/imports
cat data/import-report.txt
```

- 1 行ごとに `REQUIRES_NEW` のトランザクションで処理するため、1 行の失敗が全体を巻き戻さない
- 成功/失敗件数と、失敗した行番号・理由を `data/import-report.txt` に出力（行の内容そのものは出力しない）
- `inputFile` ジョブパラメータで、取込ディレクトリ（既定 `data/import/`、`ORDER_IMPORT_DIR` /
  `-Dorder.import-dir` で変更可）に置いた CSV を指定可能。指定できるのは英数字・`.`・`_`・`-` だけからなる
  `.csv` のファイル名だけで、パスやシンボリックリンクでディレクトリの外は読めない

```bash
mkdir -p data/import && cp /tmp/orders.csv data/import/
curl -s -X POST 'http://localhost:8080/api/batch/imports?inputFile=orders.csv'
```

### 3. 実行履歴・再実行

```bash
curl -s 'http://localhost:8080/api/batch/executions?count=10'
curl -s -X POST http://localhost:8080/api/batch/executions/1/restart
```

JBeret のジョブリポジトリは PostgreSQL の JDBC リポジトリ（`quarkus.jberet.repository.type=jdbc`）を使うため、実行履歴と checkpoint が DB に残ります。


## OpenAPI と DTO 自動生成
### DTO を OpenAPI から生成（contract-first）

`order-api/src/main/openapi/order-api.yaml` が API の契約です。
`openapi-generator-maven-plugin` の `jaxrs-spec` ジェネレーターで、DTO を **Maven の `generate-sources` フェーズで自動生成**しています。

```bash
# 生成だけ実行する場合
mvn -pl order-api generate-sources

# 通常のビルドでも generate-sources は自動実行される
mvn clean package
```

生成先:

```
order-api/target/generated-sources/openapi/src/gen/java/com/example/order/api/model/
├── CreateOrderLine.java
├── CreateOrderRequest.java
├── OrderResponse.java
├── InvoiceResponse.java
├── BatchStartResponse.java
└── ...
```

設定のポイント:

- `generatorName=jaxrs-spec`
- `generateApis=false`（今回は DTO のみ。`true` にすると `OrdersApi` などの JAX-RS インターフェースも生成）
- `useJakartaEe=true` で `jakarta.ws.rs` / `jakarta.validation`
- `useBeanValidation=true` で `@NotNull` / `@Size` / `@Min` を生成
- `dateLibrary=java8-localdatetime` で `LocalDate` / `LocalDateTime`
- `useSwaggerAnnotations=false` / `openApiNullable=false` で余計なアノテーションを抑制
- 生成モデルは Jackson アノテーションを付けますが、実行時は JSON-B が使うため無視されます（`jackson-annotations` はコンパイル用）
- `order-api/pom.xml` の `<quiet>true</quiet>` で OpenAPI Generator の donate バナーを抑制しています
- Entity は OpenAPI から生成せず、`order-domain` の JPA エンティティを別に維持

JAX-RS インターフェースも生成する場合は、`generateApis` を `true` に変更し、生成された `OrdersApi` などを Resource クラスで `implements` します。

### OpenAPI ドキュメント（code-first）

現在の Resource から OpenAPI ドキュメントも生成できます。

- Quarkus: `http://localhost:8080/q/openapi`（Swagger UI: `/q/swagger-ui`）
- Open Liberty: `http://localhost:8080/openapi`（UI: `/openapi/ui`）

Quarkus は `quarkus-smallrye-openapi`、Liberty は `mpOpenAPI-4.0` フィーチャーで有効化しています。
これは JAX-RS のアノテーションから実行時に生成する方式です。

契約を先に固定したい場合は上記の contract-first、既存コードから仕様を出したい場合は code-first、
両方を併用する場合は「OpenAPI 定義 → DTO/インターフェース生成」＋「実行時に仕様を公開」が可能です。


## テスト
```bash
mvn test
```

- `OrderApiTest`: 注文作成 → 確定 → 在庫減 → キャンセル → 在庫戻し、入力チェック、404、同一商品の複数行による在庫超過、`Location` ヘッダ
- `BatchJobTest`: 請求書チャンクジョブ、CSV バッチレットを実際に起動して完了まで待機

起動後のスモークテスト:

```bash
./scripts/smoke-test.sh http://localhost:8080
```

テストは **`io.zonky.test:embedded-postgres`** で PostgreSQL 17.11 のネイティブバイナリを
Maven 経由で取得し、テスト用に起動します。**Docker は不要**です。

- `PostgresTestResource`（`QuarkusTestResourceLifecycleManager`）がテスト開始時に起動
- ランダムポートを使うため、ローカルの PostgreSQL や他のテストと競合しない
- テスト終了時に自動停止
- 初回はバイナリの展開が入るため数秒かかる
- PostgreSQL の `initdb` は root では実行できないため、CI でも非 root ユーザーで実行する
- Docker 前提の Testcontainers に戻したい場合は、`PostgresTestResource` を差し替えるだけでよい

```java
// order-quarkus/src/test/java/com/example/order/PostgresTestResource.java
postgres = EmbeddedPostgres.builder()
        .setPort(0)
        .start();
return Map.of(
        "quarkus.datasource.jdbc.url", postgres.getJdbcUrl("postgres", "postgres"),
        "quarkus.datasource.username", "postgres",
        "quarkus.datasource.password", "postgres");
```

PostgreSQL のバイナリバージョンは親 POM の `embedded-postgres-binaries-bom` で固定しています（17.11.0）。
