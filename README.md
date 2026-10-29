# Jakarta EE Order Sample（Quarkus メイン / Liberty・WildFly・GlassFish 対応）

Spring Boot を使わず、**Jakarta EE の仕様 API だけ**でオンライン処理とバッチ処理を実装したサンプルです。
メインの実行基盤は **Quarkus 3.40 LTS**、バッチは **Jakarta Batch 2.1**（Quarkiverse JBeret）で動かします。
ビジネスロジックと Web/Batch の実装は `order-core` に閉じ込め、**Open Liberty / WildFly / GlassFish でも同じコード**が動くようにしてあります。

- 詳細な比較: [docs/runtime-benchmarks.md](docs/runtime-benchmarks.md)
- Jakarta Data 1.0 の導入検討: [docs/jakarta-data-assessment.md](docs/jakarta-data-assessment.md)
- Spring Boot との機能差を埋める検討: [docs/spring-parity-assessment.md](docs/spring-parity-assessment.md)
- `main` は Quarkus をメインに、Open Liberty / WildFly / GlassFish のアダプタも同じリポジトリに含みます

| ディレクトリ | 実行基盤 |
|---|---|
| `order-quarkus` | Quarkus 3.40 |
| `order-liberty` | Open Liberty 26 |
| `order-wildfly` | WildFly 42 |
| `order-glassfish` | GlassFish 8 |

## 動作イメージ

ログイン → 商品一覧 → 請求書バッチ実行 → 請求書一覧 → 注文一覧の流れです。

![動作イメージ](docs/images/demo.gif)

### 実測サマリ

| ランタイム | 起動時間 | c=100 スループット | 負荷後 RSS |
|---|---:|---:|---:|
| **Quarkus** | **2.65 秒** | **18,200 req/s** | **660 MB** |
| Open Liberty | 7.68 秒 | 8,700 req/s | 821 MB |
| WildFly 42 | 6.42 秒 | 8,000 req/s | 755 MB |
| GlassFish 8 | 9.73 秒 | 6,600 req/s | 675 MB |

測定条件・p99・メモリ内訳は [docs/runtime-benchmarks.md](docs/runtime-benchmarks.md) を参照してください。

> 16 vCPU、コア固定なし、DB 初期化、3回の中央値です。`ab` は約 0.5 コアで、クライアントはボトルネックではありません。

## 構成

```
order-sample/
├── order-api/        OpenAPI 定義から生成した DTO（+ 必要なら JAX-RS インターフェース）
├── order-domain/     JPA エンティティ（Jakarta Persistence のみ）
├── order-core/       Jakarta EE API だけで実装した本体
│   ├── web/          JAX-RS リソース + エラーマッピング（Controller 相当）
│   ├── service/      CDI + @Transactional（Service 相当）
│   ├── repository/   EntityManager / JPQL を隠蔽（Repository 相当）
│   ├── batch/        Jakarta Batch の Reader / Processor / Writer / Batchlet
│   └── resources/
│       ├── META-INF/persistence.xml
│       ├── META-INF/batch-jobs/invoiceJob.xml
│       ├── META-INF/batch-jobs/orderImportJob.xml
│       └── META-INF/resources/index.html   ← 簡易 UI（Quarkus/Liberty 共通）
├── order-quarkus/    Quarkus 実行アダプタ（Quarkus 依存はここだけ）
├── order-liberty/    Open Liberty 実行アダプタ（WAR + server.xml + EJB）
├── order-wildfly/    WildFly 実行アダプタ（WAR + jboss-web.xml + EJB）
├── order-glassfish/  GlassFish 実行アダプタ（WAR + EJB）
└── docs/             ランタイム比較などのドキュメント
```

依存方向は `web → service → repository → domain` です。Controller は Service だけを、
Service は Repository だけを参照し、JPQL や `EntityManager` は Repository に閉じ込めています。
Web の DTO は `order-api` の OpenAPI 定義から生成し、`order-core` はその生成物を利用します。

`order-core` は Quarkus に依存せず、Jakarta EE API と `order-api` / `order-domain` にだけ依存しています。
Quarkus と Liberty の主な差分は次のとおりです（初期データ投入は CDI の `Startup` イベントで共通化しています）。

| 関心事 | Quarkus 側 | Open Liberty 側 |
|---|---|---|
| バッチ実行 | `quarkus-jberet`（Jakarta Batch 2.1 実装） | `batch-2.1`（`jakartaee-11.0` に含まれる） |
| スケジューラ | `quarkus.jberet.job."invoiceJob".cron` | EJB `@Schedule`（`InvoiceSchedule`） |
| 初期データ投入 | CDI `Startup` イベント（`StartupDataSeeder`、共通） | 同左 |
| EntityManager の注入 | Quarkus の組み込み Bean | Liberty の JPA コンテナが提供する CDI Bean |

## 動作環境

- Java 21（このリポジトリは `maven.compiler.release=21`）
- Maven 3.9+
- Docker は**任意**（ローカル実行で Docker Compose を使う場合のみ。テストは Docker 不要）

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## PostgreSQL を起動

### 方法A: Docker Compose（任意）

```bash
docker compose up -d
docker compose ps
```

- DB: `orders`
- ユーザー: `orders`
- パスワード: `orders`
- ポート: `5432`

停止する場合:

```bash
docker compose down
```

DB も含めて初期化する場合:

```bash
docker compose down -v
```

### 方法B: Docker なし（Maven + Embedded PostgreSQL）

`io.zonky.test:embedded-postgres` は、Maven で取得した PostgreSQL のネイティブバイナリを
そのまま実行します。Docker デーモンもコンテナも不要です。

```bash
# 依存モジュールをローカルリポジトリへ入れる
mvn install -DskipTests

# PostgreSQL 17.11 を 5432 で起動（前面で動作し続ける）
mvn -pl order-quarkus -Pembedded-postgres test-compile exec:java
```

起動すると次の接続情報で待ち受けます。別ターミナルで Quarkus を起動してください。

- JDBC URL: `jdbc:postgresql://localhost:5432/orders`
- ユーザー: `orders`
- パスワード: `orders`
- 停止: `Ctrl+C`
- 初回はバイナリの展開が入るため数秒かかります
- PostgreSQL の `initdb` は root では実行できないため、**非 root ユーザー**で実行してください
- Docker Compose の PostgreSQL と同時に使わないでください（5432 が競合します）

ランチャーは `order-quarkus/src/test/java/com/example/order/EmbeddedPostgresLauncher.java` です。

### 方法の比較

| 方法 | Docker | 実 PostgreSQL | 向き |
|---|---|---|---|
| Embedded PostgreSQL（Zonky） | 不要 | ○ | テスト、Docker なしのローカル実行（このサンプル） |
| Testcontainers | 必要 | ○ | テスト（Docker 前提） |
| Docker Compose | 必要 | ○ | ローカル開発 |
| OS に直接インストール | 不要 | ○ | ローカル / CI のサービス |
| H2 の PostgreSQL 互換モード | 不要 | × | 簡易テストのみ（非推奨） |

## Quarkus で起動

```bash
mvn clean package
java -Dorder.demo-users=true -jar order-quarkus/target/quarkus-app/quarkus-run.jar
```

`-Dorder.demo-users=true`（環境変数なら `ORDER_DEMO_USERS=true`）はデモユーザーを作るためのローカル専用の設定です。

- 画面: <http://localhost:8080/>（ログイン、注文のステータス絞り込み、注文・商品・請求書のページング / ソート、バッチ実行に対応）
- UI は MUI のデフォルトテーマ（Roboto / `#1976d2` / 4px 角丸 / Elevation）に準拠し、モバイルではテーブルを横スクロール
- アイコンは Font Awesome Free 6.7.2 の `file-circle-check` をインライン SVG で使用（ヘッダー / ログイン / favicon 共通、CC BY 4.0）
- Lighthouse（mobile / desktop、ログイン / ダッシュボード）: Performance / Accessibility / Best Practices / SEO すべて 100
- ログイン画面: <http://localhost:8080/login.html>
- API: <http://localhost:8080/api/products>
- DB: `jdbc:postgresql://localhost:5432/orders`
- 初回起動時に顧客 3 件、商品 4 件、前月の確定注文 3 件、当月の DRAFT 注文 1 件を投入します。ユーザーは下記「初期ユーザー」の設定に従って作成します。


開発モードで動かす場合:

```bash
mvn install -DskipTests
mvn -pl order-quarkus quarkus:dev
```

> `order-quarkus` は `order-core` / `order-domain` を JAR 依存として参照します。リアクタ経由で `quarkus:dev` を実行すると依存 JAR が見つからないことがあるため、先に `mvn install` してください。

## ログイン / 認証

- 初期ユーザーは `app_users` が空のときだけ作成し、パスワードはシステムプロパティか環境変数で渡します

| 設定（システムプロパティ / 環境変数） | 内容 |
|---|---|
| `order.admin-password` / `ORDER_ADMIN_PASSWORD` | `admin`（`ADMIN`）をこのパスワードで作成 |
| `order.user-password` / `ORDER_USER_PASSWORD` | `user`（`USER`）をこのパスワードで作成 |
| `order.demo-users=true` / `ORDER_DEMO_USERS=true` | パスワード未指定のユーザーを `admin` / `admin`、`user` / `user` で作成（ローカル専用） |

  DB の接続情報も環境変数で上書きできます（既定値はローカル開発用）。
  Quarkus は `application.properties` の `${DB_USER:orders}` 形式、Liberty は `server.xml` の `<variable>` で参照します。
  WildFly / GlassFish の設定手順は `docs/runtime-setup.md` を参照してください。

| 環境変数 | 既定値 | 内容 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | JDBC URL |
| `DB_USER` | `orders` | DB ユーザー |
| `DB_PASSWORD` | `orders` | DB パスワード（`docker compose` も同じ変数を使います） |

  どれも設定しないとユーザーは作成されず、ログインできません（起動ログに WARNING が出ます）。
  `quarkus:dev`、Quarkus のテスト、Liberty（`src/main/liberty/config/jvm.options`）では `order.demo-users=true` を設定済みです。
  `scripts/smoke-test.sh` は `user` / `user` でログインするため、デモユーザーを有効にしたサーバーに対して実行してください。
- ブラウザは `/api/auth/session` で `ORDER_SESSION`（`HttpOnly`）と `XSRF-TOKEN` Cookie を受け取ります
- SPA の初期化は公開の `/api/auth/status` で認証状態を確認します
- 直接 API は `/api/auth/login` で Bearer トークンを取得します
- 注文・商品・顧客・請求書は `USER` / `ADMIN`、バッチは `ADMIN` のみ利用できます
- 認可は「拒否がデフォルト」です。リソースメソッド（またはクラス）に `@RequireRole`・`@PublicEndpoint`・`@OptionalAuth`
  のどれも付いていなければ 403 になります。公開しているのは `login`・`session`（`@PublicEndpoint`）と `status`（`@OptionalAuth`）だけです
- Cookie 認証の POST / PUT / PATCH / DELETE には `X-XSRF-TOKEN` ヘッダーが必要です。Bearer 認証では不要です
- パスワードは PBKDF2-HMAC-SHA256（600,000 回、JDK 標準）で `$pbkdf2-sha256$i=…$<salt>$<hash>` 形式で保存します。
  反復回数を変えると、次回ログイン時に新しい回数で再ハッシュされます
- 同じアカウントで 5 回続けてログインに失敗すると、15 分間ロックします。ロック中・存在しないユーザー・パスワード誤りは
  すべて同じ 401 を返します。第三者がわざと失敗して他人をロックできるため、ロック時間は短めにしています。
  IP 単位の制限はリバースプロキシや WAF で行ってください
- セッションは絶対期限 8 時間に加えて、30 分間アクセスがないと失効します（アイドルタイムアウト）。
  同時に保持できるのは 1 ユーザー 10 セッションまでで、11 個目にログインすると最も古いセッションが失効します
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

## Open Liberty への切り替え

`order-liberty` は同じ `order-core` を WAR に包んだアダプタです。

1. PostgreSQL の JDBC ドライバを Liberty のサーバー設定 `lib` に置く

   ```bash
   mkdir -p order-liberty/src/main/liberty/config/lib
   find ~/.m2/repository/org/postgresql/postgresql -name 'postgresql-*.jar' | head -1 \
     | xargs -I{} cp {} order-liberty/src/main/liberty/config/lib/
   ```

2. PostgreSQL を起動しておく（Docker Compose または Embedded PostgreSQL ランチャー）

3. WAR を作る

   ```bash
   mvn -pl order-liberty -am package
   ```

4. Liberty を起動する（Quarkus は停止しておく。どちらも 8080 を使います）

   ```bash
   mvn -pl order-liberty liberty:create
   mvn -pl order-liberty liberty:install-feature
   mvn -pl order-liberty liberty:deploy
   mvn -pl order-liberty liberty:run
   ```

`server.xml` のポイント:

- `jakartaee-11.0` で JAX-RS / CDI / JPA / Bean Validation / Batch / JSON-B / EJB を有効化
- `jpa defaultJtaDataSourceJndiName="jdbc/orderDB"` で、`persistence.xml` に `jta-data-source` を書かずに済ませる
- PostgreSQL は `jdbcDriver` + `library` で参照（接続情報は `DB_URL` / `DB_USER` / `DB_PASSWORD` の `<variable>`）
- `ibm-web-ext.xml` でコンテキストルートを `/` にし、Quarkus と同じ `/api/...` でアクセス
- バッチはアプリが未認証の主体として `JobOperator` を呼ぶため、`batchSubmitter` / `batchMonitor` を `EVERYONE` に設定。
  `batchAdmin`（他ユーザーのジョブ管理）は使わないので定義しない
- EJB コンテナの ORB がユーザーレジストリを要求するため、**ユーザーなしの** `basicRegistry` を定義
  （コンテナ側の `admin/admin` は持たない。認証はアプリのトークンで行う）
- `localConnector-1.0` はアプリからもドキュメントからも使わないため有効化しない

> `main` の Open Liberty アダプタは、Open Liberty 26.0.0.10 で **API・バッチ・OpenAPI の起動確認済み**です。Quarkus 版と同じ `order-core` / 生成 DTO / ジョブ XML を使っています。

### WildFly / GlassFish の datasource

`persistence.xml` は `jta-data-source` を指定せず、コンテナのデフォルト datasource を使う構成です。
WildFly / GlassFish では PostgreSQL をデフォルト datasource に向ける必要があります。
具体的な `jboss-cli` / `asadmin` の例は [docs/runtime-setup.md](docs/runtime-setup.md) を参照してください。

- **WildFly**: JDBC ドライバをモジュールとして追加し、`ee` サブシステムの `default-bindings` の datasource を変更
- **GlassFish**: JDBC ドライバを domain の `lib` に置き、`jdbc/__default` の pool を PostgreSQL に変更
- **GlassFish**: `order-glassfish/src/main/webapp/WEB-INF/glassfish-web.xml` で context root を `/` に設定済み

Quarkus / Open Liberty / WildFly / GlassFish の4ランタイムで、起動・一覧 API・注文の作成/確定/キャンセル・請求書バッチ・CSV 取込を確認済みです（[docs/runtime-setup.md](docs/runtime-setup.md)）。
自動テストが動くのは現状 Quarkus のみです。

### 実測比較

最新の起動時間・メモリ・スループット・p99 は
[docs/runtime-benchmarks.md](docs/runtime-benchmarks.md) にまとめています。

## 設計上の注意

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
- Liberty / WildFly / GlassFish: EJB `@Schedule`

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
[docs/jakarta-data-assessment.md](docs/jakarta-data-assessment.md) にまとめています。

### Spring Boot との機能差

Spring Boot の AutoConfiguration / Starter / DevTools は Jakarta EE の仕様だけでは再現できませんが、
CDI Startup イベント、JPA 監査、CDI トランザクションイベント、CDI インターセプタ、MP Health などで
プログラミングモデルと運用機能は近づけられます。優先度と基盤ごとの差は
[docs/spring-parity-assessment.md](docs/spring-parity-assessment.md) にまとめています。

### 既知の制約

- 起動のたびに `drop-and-create` でテーブルを作り直すため、オンラインの注文データは消えます（デモ用）
- 一覧 API は `PageResponse`（`content` / `page` / `size` / `totalElements` など）を返します。旧来の配列レスポンスとは互換性がありません
- CDI `Startup` イベントによる初期データ投入は 4 基盤で確認済みです。WildFly / GlassFish の CSV 取込は、サーバーの作業ディレクトリ配下に書き込み可能な `data` ディレクトリが必要です
- CSV 取込は再実行すると同じ注文を再度作成します（重複排除は未実装）
- 認証はアプリ発行の opaque token（Cookie / Bearer）です。Jakarta Security / OIDC ではありません
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
| `@Scheduled` | Quarkus: JBeret cron / Liberty: EJB `@Schedule` |
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
[docs/spring-parity-assessment.md](docs/spring-parity-assessment.md) を参照してください。
