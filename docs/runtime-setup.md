# WildFly / GlassFish セットアップ

`order-core` の `persistence.xml` は `jta-data-source` を指定せず、**コンテナのデフォルト datasource** を使います。
そのため、WildFly / GlassFish では PostgreSQL をデフォルト datasource に向ける必要があります。

以下は PostgreSQL 17 が `localhost:5432/orders`（ユーザー `orders` / パスワード `orders`）で動いている前提の例です。
パスやバージョンは環境に合わせて読み替えてください。

## WildFly 42

`standalone-full.xml` で起動します（EJB / Batch が必要なため）。

### 1. JDBC ドライバをモジュールとして追加

```bash
$JBOSS_HOME/bin/jboss-cli.sh --connect

module add \
  --name=org.postgresql \
  --resources=/path/to/postgresql-42.7.13.jar \
  --dependencies=java.sql,jakarta.transaction.api
```

### 2. datasource を作成

```bash
/subsystem=datasources/jdbc-driver=postgresql:add(\
  driver-name=postgresql,\
  driver-module-name=org.postgresql,\
  driver-class-name=org.postgresql.Driver)

data-source add \
  --name=OrderDS \
  --jndi-name=java:jboss/datasources/OrderDS \
  --driver-name=postgresql \
  --connection-url='${env.DB_URL}' \
  --user-name='${env.DB_USER}' \
  --password='${env.DB_PASSWORD}' \
  --min-pool-size=10 \
  --max-pool-size=100
```

`max-pool-size` の既定値は 20 です。同時接続が多いと、この値がスループットの上限になります
（c=100 では products が約 4.9k → 8.4k req/s に改善するため、100 を設定しています）。

接続情報は式のまま管理モデルに保存され、起動時に環境変数から解決されます。WildFly を起動する前に
`DB_URL`・`DB_USER`・`DB_PASSWORD` を環境変数で渡してください（未設定だと datasource の解決に失敗します）。
ローカルで試すときだけ、`--connection-url=jdbc:postgresql://localhost:5432/orders --user-name=orders --password=orders`
のように直接指定してもかまいません。

### 3. デフォルト datasource を変更

```bash
/subsystem=ee/service=default-bindings:write-attribute(\
  name=datasource,\
  value=java:jboss/datasources/OrderDS)
```

### 4. JSON-B を優先する

生成 DTO には Jackson アノテーションが付いているため、WildFly は既定で Jackson に切り替わり
`WFLYRS0018` を出します。サンプルは JSON-B を前提にしているので、システムプロパティで JSON-B を優先します。

```bash
/system-property=resteasy.preferJacksonOverJsonB:add(value=false)
reload
```

### 5. 初期ユーザーの設定

初期ユーザーのパスワードはシステムプロパティで渡します（環境変数 `ORDER_ADMIN_PASSWORD` なども可）。
ローカルのデモでは `order.demo-users=true` で `admin/admin`・`user/user` を作れます。本番では使わないでください。

```bash
/system-property=order.demo-users:add(value=true)
# 本番: /system-property=order.admin-password:add(value=...) は管理モデルに平文で残るため、
#       環境変数 ORDER_ADMIN_PASSWORD を使うことを推奨
```

### 6. デプロイ

```bash
mvn -pl order-wildfly -am package
cp order-wildfly/target/order-sample-wildfly.war \
  $JBOSS_HOME/standalone/deployments/
```

## GlassFish 8

フルプロファイルで起動します（EJB / Batch が必要なため）。

### 1. JDBC ドライバを domain の lib に配置

```bash
cp /path/to/postgresql-42.7.13.jar \
  $GLASSFISH_HOME/glassfish/domains/domain1/lib/
```

### 2. コネクションプールを作成

```bash
# パスワードはエイリアスに預ける（マスターパスワードで暗号化され、domain.xml に平文が残らない）
asadmin create-password-alias orderDbPassword

asadmin create-jdbc-connection-pool \
  --datasourceclassname org.postgresql.ds.PGSimpleDataSource \
  --restype javax.sql.DataSource \
  --steadypoolsize 10 --maxpoolsize 100 --statementcachesize 100 \
  --property "user=${DB_USER:-orders}:password=\${ALIAS=orderDbPassword}:serverName=${DB_HOST:-localhost}:portNumber=${DB_PORT:-5432}:databaseName=${DB_NAME:-orders}" \
  orderPool
```

`create-password-alias` はプロンプトでパスワードを聞いてくるので、シェルの履歴には残りません。
既存の pool を更新するときは `asadmin set 'resources.jdbc-connection-pool.orderPool.property.password=${ALIAS=orderDbPassword}'`
のように設定し直してください。

### 3. デフォルト datasource を PostgreSQL に向ける

GlassFish の JPA デフォルト datasource は `jdbc/__default` です。
`jdbc/__default` は `system-all-req` のため削除・再作成はできません。次のように pool を変更します。

```bash
asadmin set 'resources.jdbc-resource.jdbc/__default.pool-name=orderPool'
asadmin list-jdbc-resources
```

### 4. 初期ユーザーの設定

初期ユーザーのパスワードは JVM オプションで渡します（環境変数 `ORDER_ADMIN_PASSWORD` なども可）。
ローカルのデモでは `order.demo-users=true` で `admin/admin`・`user/user` を作れます。本番では使わないでください。

```bash
asadmin create-jvm-options '-Dorder.demo-users=true'
asadmin restart-domain
```

### 5. デプロイ

```bash
mvn -pl order-glassfish -am package
asadmin deploy --contextroot / \
  order-glassfish/target/order-sample-glassfish.war
```

`order-glassfish` には `WEB-INF/glassfish-web.xml` があり、context root は `/` に設定済みです。
root context でデプロイするため、起動時に次のような GlassFish の警告が出ることがありますが、動作には影響しません。

```text
[WARNING] Context path from ServletContext:  differs from path from bundle: /
```

## スモークテスト

起動後に共通のスクリプトで確認できます。

```bash
./scripts/smoke-test.sh http://localhost:8080
```

`/api/products` と `/api/customers` が 200 を返し、初期データが入っていることを確認します。

## CSV 取込のレポート出力先

`OrderImportBatchlet` はサーバープロセスの作業ディレクトリ配下に `data/import-report.txt` を出力します。
`inputFile` で指定する CSV は取込ディレクトリ（既定は作業ディレクトリ配下の `data/import/`）から読みます。
取込ディレクトリはシステムプロパティ `order.import-dir` または環境変数 `ORDER_IMPORT_DIR` で変更できます。
WildFly の Docker イメージのように作業ディレクトリが書き込み不可の場合は、起動前に書き込み可能な
`data` ディレクトリを作成するか、書き込み可能なディレクトリでサーバーを起動してください。

```bash
# WildFly Docker イメージの例
docker exec -u root <container> sh -c 'mkdir -p /opt/jboss/data && chown jboss:jboss /opt/jboss/data'
```

## セキュリティヘッダー

すべてのレスポンス（静的ファイルを含む）に次のヘッダーを付けます。

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
                         img-src 'self' data:; frame-ancestors 'none'; base-uri 'none';
                         form-action 'self'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
```

- **Quarkus**: `order-quarkus/src/main/resources/application.properties` の `quarkus.http.header.*`
- **Open Liberty / WildFly / GlassFish**: 各ランタイムの `SecurityHeadersFilter`（`@WebFilter("/*")`）
  - Servlet API は Quarkus に無く、core に置くと Quarkus で読まれないため、ランタイムごとに同じコードを持たせています

CSP は `'unsafe-inline'` を許可していないため、UI にインラインの `<script>`・`<style>`・`onclick` などの
イベントハンドラ・`style="..."` 属性を書くことはできません。JS は `app.js` / `login.js`、
CSS は `index.css` / `login.css` に置き、ボタンは `data-action` 属性とイベント委譲で処理します。

HSTS（`Strict-Transport-Security`）はアプリではなく TLS 終端で付けます。リバースプロキシの例:

```nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

`X-Forwarded-Proto` を信頼する構成では、`order.trust-forwarded-proto=true` と
`order.cookie-secure=always` を設定してください（詳細は README の設定表）。

## リクエストサイズの上限

| ランタイム | 設定 | 値 |
|---|---|---|
| Quarkus | `quarkus.http.limits.max-body-size` | 1 MiB |
| Open Liberty | アプリ側では未設定（`httpEndpoint` にボディ上限の属性がない） | リバースプロキシで制限 |
| WildFly | `http-listener` の `max-post-size` | 既定 10 MiB |
| GlassFish | `http-listener` の `maxPostSize` | 既定 2 MiB |

既定値はバージョンによって変わるため、本番では明示的に設定してください。厳密な上限が必要な場合は
TLS 終端のリバースプロキシ（nginx の `client_max_body_size` など）で設定するのが確実です。

項目ごとの上限（`maxLength` / `maxItems` / `maximum`）は `order-api/src/main/openapi/order-api.yaml` に
定義してあり、Bean Validation で 400 になります。

## 起動時の警告

- **Open Liberty**: 空の DB への初回起動では EclipseLink の `DROP TABLE` 警告が出ます。
  `persistence.xml` の `eclipselink.logging.exceptions=false` で抑制しています。DDL を調査するときは外してください。
- **WildFly**: `WFLYRS0018` は上記の `resteasy.preferJacksonOverJsonB=false` で解消します。
- **GlassFish**: root context の `Context path from ServletContext ...` 警告は動作に影響しません。

## 動作確認済みの組み合わせ

2026-10-29 時点で、以下を実際に起動して確認しました。

| ランタイム | バージョン | 起動 | 一覧 API | 注文作成→確定→キャンセル | 請求書バッチ | CSV 取込 |
|---|---|---|---|---|---|---|
| Quarkus | 3.40.1 | ○ | ○ | ○ | ○ | ○ |
| Open Liberty | 26.0.0.10 | ○ | ○ | ○ | ○ | ○ |
| WildFly | 42.0.0.Final | ○ | ○ | ○ | ○ | ○ |
| GlassFish | 8.0.5 | ○ | ○ | ○ | ○ | ○ |

### 2026-10-29 の再確認（CDI Startup / ページング / ソート / Problem Details / 認証）

| ランタイム | 起動 | 初期データ（CDI Startup） | ページング / ソート | Problem Details | 認証（Cookie / Bearer / ロール） | 請求書バッチ | CSV 取込 |
|---|---|---|---|---|---|---|---|
| Quarkus | ○ | ○ | ○（テスト 81 件） | ○ | ○ | ○ | ○ |
| Open Liberty | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| WildFly | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| GlassFish | ○ | ○ | ○ | ○ | ○ | ○ | ○ |

確認方法:

- **Quarkus**: `mvn -pl order-quarkus -am test`（Embedded PostgreSQL）
- **Open Liberty**: `liberty:create` / `liberty:install-feature` / `liberty:deploy` で 26.0.0.10 を起動
- **WildFly**: `quay.io/wildfly/wildfly:42.0.0.Final-jdk21` を `standalone-full.xml` で起動し、
  PostgreSQL datasource と `resteasy.preferJacksonOverJsonB=false` を設定
- **GlassFish**: `ghcr.io/eclipse-ee4j/glassfish:8.0.5` を起動し、`domain1/lib` に PostgreSQL ドライバを配置、
  `jdbc/__default` の pool を `orderPool` に変更

認証の確認内容:

- 未認証の `/api/orders` は 401
- `POST /api/auth/login` で Bearer トークンを取得し、`/api/orders` は 200
- `USER` トークンで `/api/batch/executions` は 403、`ADMIN` トークンは 200
- `POST /api/auth/session` の Cookie で `/api/auth/me` は 200

補足:

- サンプルの `server.xml` は起動を速くするため `jakartaee-11.0` を使わず、必要な feature だけを列挙しています。
  Jakarta Data を使う場合は `data-1.0` を追加してください。
  本サンプルは Jakarta Data の Repository を使っていません。
- WildFly / GlassFish の CSV 取込は、上記の「CSV 取込のレポート出力先」に記載した
  書き込み可能な `data` ディレクトリが必要です。
- GlassFish の管理 CLI は、コンテナ内では `--host localhost --port 4848` を明示すると接続できます。
- JAX-RS リソースには `@RolesAllowed` を付けず、`@RequireRole` を `RoleAuthorizationFilter` で検査しています。
  GlassFish で `@RolesAllowed` を付けるとコンテナ認証が起動し、`/api/auth/login` まで 401 になるためです。
