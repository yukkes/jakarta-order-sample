# 性能とコンテナサイズ

同一の `order-core` を 4 基盤 + native で動かした測定結果と、コンテナイメージの最小サイズです。
起動手順は [runtimes.md](runtimes.md)、API の使い方は [usage.md](usage.md) を参照してください。

同一の `order-core` / `order-api` / `order-domain` を Quarkus / Open Liberty / WildFly / GlassFish で動かし、
同じ条件で起動時間・メモリ・スループットを測定しました。

今回の数値は **2026-10-29 に Quarkus REST と Concurrency 3.1 を適用した後**に再測定したものです。
WSL2 ホストの負荷変動により、前回の表と絶対値を直接比較しないでください。同一ホストで続けて測った
RESTEasy Classic との A/B は「[Quarkus REST への移行（A/B）](#quarkus-rest-への移行ab)」にあります。

## 条件

- 16 vCPU（8 物理コア / SMT）、メモリ 8 GB、WSL2
- Java 21（Quarkus native のみ Mandrel 25.0.4.1）
- PostgreSQL 17（Docker、各測定の前にスキーマを作り直す）
- サーバーとクライアントの CPU 固定なし
- **スループット: `wrk -t4 -c100 -d10s --latency` を 3 回、中央値**
  - `ab` は 20k req/s 付近でクライアント側がボトルネックになるため、wrk に変更した
  - keep-alive 有効。認証は `/api/auth/login` のアクセストークン（JWT）を `Authorization: Bearer` で送る
- 起動時間: プロセス / コンテナ起動から `/api/auth/status` が 200 を返すまで（3 回の中央値）
- メモリ: 起動直後の RSS（Quarkus / Liberty / WildFly は `ps`、GlassFish は `docker stats`）
- 認証: HS256 のステートレスなアクセストークン。**リクエストごとの DB アクセスはない**
- 3 種類のエンドポイントを測る
  - `/api/auth/status`: 認証なし・DB なし（HTTP / JSON 層の上限）
  - `/api/auth/me`: 認証のみ（JWT 検証、DB 0 回）
  - `/api/products?page=0&size=100`: 認証 + 商品一覧の DB クエリ（DB 1 回）

## 結果

各ランタイムで**最も良い設定**（プール 100、WildFly は Jackson）に揃え、各エンドポイントを
ウォームアップしてから測定した値です。認証は HS256 の JWT で、JSON-P のプロバイダは
1 回だけ解決して使い回します（以前はリクエストごとに ServiceLoader で探索していた）。

| ランタイム | 起動 | 起動直後 RSS | status（認証なし） | me（認証のみ） | products（認証 + DB） |
|---|---:|---:|---:|---:|---:|
| **Quarkus 3.40（JVM / Quarkus REST）** | 3.09 秒 | 305 MB | **160,372** | **132,407** | **13,886** |
| **Quarkus 3.40（native）** | **1.12 秒** | **132 MB** | 96,288 | 77,787 | 10,115 |
| Open Liberty 26 | **5.92 秒** | 445 MB | 42,917 | 50,086 | 10,800 |
| WildFly 42（Jackson / slim） | **6.01 秒** | 502 MB | 61,002 | 53,958 | 11,080 |
| GlassFish 8 | 9.49 秒 | 519 MB | 27,006 | 23,788 | 3,590 |

p99（中央値）:

| ランタイム | status | me | products |
|---|---:|---:|---:|
| Quarkus JVM | 4.1 ms | 4.2 ms | 15.8 ms |
| Quarkus native | 8.7 ms | 12.4 ms | 34.6 ms |
| Open Liberty | 5.5 ms | 5.5 ms | 18.4 ms |
| WildFly | 11.9 ms | 12.3 ms | 21.2 ms |
| GlassFish | 6.3 ms | 6.8 ms | 30.6 ms |

## Quarkus REST への移行（A/B）

Quarkus 3.40 で RESTEasy Classic（`quarkus-resteasy` + `quarkus-resteasy-jsonb`）から
Quarkus REST（`quarkus-rest` + `quarkus-rest-jsonb`）へ移行しました。同じホストで続けて測った結果です。

| 項目 | RESTEasy Classic | Quarkus REST | 差 |
|---|---:|---:|---:|
| status | 141,788 | **160,372** | +13% |
| me | 121,408 | **132,407** | +9% |
| products | 14,344 | 13,886 | -3%（誤差の範囲） |
| 起動 | 3.13 秒 | 3.09 秒 | ほぼ同等 |
| 起動直後 RSS | 314 MB | 305 MB | -3% |

`products` は DB クエリが支配的なため差が出ず、JWT 検証と JSON 応答が中心の
`status` / `me` で Quarkus REST の効果が大きいという結果でした。

## 考察

- **JSON-P のプロバイダ解決が認証の最大コストだった**。`Json.createReader` は呼び出しごとに
  ServiceLoader でプロバイダを探すため、JWT 検証が 1 リクエストあたり 70〜80 µs かかっていた。
  プロバイダを 1 回だけ解決して使い回すと、`me` は Quarkus 11.8k → **132.4k**、
  Liberty 13.9k → 50.1k、WildFly 29.5k → 54.0k になる
- **HTTP / JSON 層**（`/api/auth/status`）は Quarkus（JVM）が最も速い（160k）。native は 96k で、
  小さな JSON では JIT の最適化が AOT を上回る
- **DB クエリを含む `products`** は Quarkus が 13.9k で最も速い。WildFly 11.1k、Liberty 10.8k、
  native 10.1k、GlassFish 3.6k。プール 100 と、Liberty は EclipseLink の L2 キャッシュ無効化が効いている
- **Quarkus REST は RESTEasy Classic より速い**。同一ホストで A/B すると status 141.8k → **160.4k**、
  me 121.4k → **132.4k**、products はほぼ同等（14.3k → 13.9k）だった
- **native image** は起動 1.12 秒・132 MB で JVM の 3.09 秒・305 MB より明確に優位。
  スループットは JVM より低い（status 96k 対 160k、products 10.1k 対 13.9k）
- GlassFish は products が最も低い（3.6k）。プール・ステートメントキャッシュ・クエリキャッシュ・
  トランザクション省略のいずれも大きな改善にならず、EclipseLink のリクエスト処理が支配的

## 起動時間とメモリ

| ランタイム | 起動 | 起動直後 RSS | 備考 |
|---|---:|---:|---|
| Quarkus native | **1.12 秒** | **132 MB** | Mandrel 25.0.4.1、バイナリ 101 MB |
| Quarkus JVM | 3.09 秒 | 305 MB | Quarkus REST |
| WildFly 42 | **6.01 秒** | 502 MB | Galleon で必要な layer だけを provisioning（`-Pslim`、EJB なし） |
| GlassFish 8 | 9.49 秒 | 519 MB | Docker コンテナ、EJB なし |
| Open Liberty 26 | **5.92 秒** | 445 MB | feature を最小構成 + `concurrent-3.1` に変更（`server start` から測定） |

## 認証方式の比較（opaque トークン → JWT + リフレッシュ）

以前はリクエストごとに `auth_tokens` を DB で照合していた。現在は HS256 のステートレスな
アクセストークン（JWT）+ リフレッシュトークン方式で、**リクエストパスから DB アクセスがなくなった**。

| エンドポイント | DB アクセス | 旧: opaque | 新: JWT |
|---|---|---:|---:|
| `/api/auth/me` | 旧 1 回 / 新 0 回 | 約 5k（DB 律速） | 11,502（JVM） / 12,075（native） / 14,044（Liberty） |
| `/api/products` | 旧 2 回 / 新 1 回 | 約 5.5k | 7,353（JVM） / 7,512（native） / 6,804（Liberty） |

- 認証のみのエンドポイントは DB 律速から解放され、**2〜3 倍**になった
- `products` は商品クエリが残るため、改善は +20〜35% にとどまる
- 失効はアクセストークンの寿命（既定 10 分）まで遅れる。リフレッシュは 10 分に 1 回だけ DB を引く

## 注意（以前の測定との関係）

- 以前の表（Quarkus 18,200 / Liberty 8,700 / WildFly 8,000 / GlassFish 6,600 req/s）は **`ab` で測定**していた。
  `ab` は 20k req/s 付近でクライアントがボトルネックになり、ランタイムの上限を測れていなかった。
  wrk で測り直すと、Quarkus の HTTP 層は 160k req/s まで出る
- 以前の数値は「認証方式が opaque（DB 照合あり）」の時代のものでもある。現在の数値と直接比較しないこと
- 数値は同一ホスト上のマイクロベンチマークであり、本番性能を保証するものではない

## native image の制約

**Jakarta Batch は native で動作しない。** 請求書バッチを開始すると
`QuarkusJobRepository.savePersistentData` で例外が発生し、プロセスが停止する。
REST API と画面（注文・商品・請求書・認証）は動作する。native を採用する場合は、
バッチを別プロセスに分けるか、JBeret の native 対応を待つ必要がある。

## 再現手順（ベンチマーク）

```bash
# PostgreSQL
docker compose up -d   # または既存の PostgreSQL（orders/orders）

# Quarkus（JVM）
mvn -pl order-quarkus -am package -DskipTests
java -Dorder.demo-users=true -jar order-quarkus/target/quarkus-app/quarkus-run.jar

# Quarkus（native）
mvn -pl order-quarkus -am package -DskipTests \
  -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
ORDER_DEMO_USERS=true ./order-quarkus/target/order-quarkus-1.0.0-SNAPSHOT-runner

# Open Liberty
cd order-liberty
mvn liberty:create liberty:install-feature liberty:deploy
./target/liberty/wlp/bin/server run orderServer

# WildFly 42 / GlassFish 8 は Docker イメージ（--network host）で起動し、
# PostgreSQL ドライバのモジュール / 接続プールを設定してから WAR をデプロイする
```

測定は `wrk -t4 -c100 -d10s --latency` を使用し、`Authorization: Bearer <token>` を付けます。

## コンテナイメージの最小サイズ

同一のアプリケーションを各ランタイムでコンテナ化したときの実測サイズです（2026-10-29）。
「最小」は、**そのランタイムの配布物をそのまま入れて動かす**前提での最小構成です。

### 結果

| ランタイム | ベースイメージ | 展開後 | 転送時（gzip） | 備考 |
|---|---|---:|---:|---|
| **Quarkus native** | `gcr.io/distroless/base-debian12` | **176 MB** | **43 MB** | 実行ファイル 101 MB |
| Quarkus JVM | `gcr.io/distroless/java21-debian12` | 353 MB | 100 MB | fast-jar 47 MB |
| Open Liberty 26（feature 最小） | `eclipse-temurin:21-jre-alpine` | 582 MB | 170 MB | `wlp` 139 MB |
| WildFly 42（Galleon slim） | `eclipse-temurin:21-jre-alpine` | 559 MB | 189 MB | サーバー 142 MB |
| GlassFish 8 | 公式 `ghcr.io/eclipse-ee4j/glassfish:8.0.5` | 1.02 GB | 336 MB | slim 配布なし |

- 展開後は `docker images` の SIZE、転送時は `docker save | gzip` のサイズ
- WildFly の公式フルイメージは **1.36 GB**、GlassFish の公式イメージは **1.02 GB**。
  WildFly は上の slim 構成にすると約 1/2.4 になります

### Dockerfile（最小構成の例）

```dockerfile
# Quarkus native
FROM gcr.io/distroless/base-debian12
COPY runner /app/app
USER 1000:1000
ENTRYPOINT ["/app/app"]
```

```dockerfile
# Quarkus JVM
FROM gcr.io/distroless/java21-debian12
WORKDIR /app
COPY . /app
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
```

```dockerfile
# Open Liberty（wlp は liberty:install-feature 後のディレクトリ）
FROM eclipse-temurin:21-jre-alpine
COPY wlp /opt/wlp
ENTRYPOINT ["/opt/wlp/bin/server", "run", "orderServer"]
```

```dockerfile
# WildFly（target/server は -Pslim で provisioning したもの）
FROM eclipse-temurin:21-jre-alpine
COPY server /opt/wildfly
ENTRYPOINT ["/opt/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
```

Liberty / WildFly は起動スクリプトがシェルを必要とするため、distroless ではなく
シェル入りの JRE イメージを使っています。

### さらに小さくする場合

| 手段 | 効果（概算） | 注意 |
|---|---|---|
| native を `scratch` に置く | 176 MB → **約 110 MB** | `--static`（musl）または静的リンクが必要。glibc 版はそのままでは動かない |
| JVM 用に `jlink` で JRE を削る | 353 MB → 250 MB 前後 | 必要なモジュールの洗い出しが必要 |
| GlassFish から `javadb` / `mq` を削除 | 1.02 GB → 800 MB 前後 | 非サポートの改造。公式に slim 配布はない |
| Liberty の feature をさらに削る | `wlp` 139 MB → 100 MB 前後 | 使う機能に合わせて `server.xml` を変更 |

**アプリケーションコードを変えずに最小になるのは Quarkus native（180 MB / 転送 44 MB）**です。
JVM 版と比べても 1/2 以下で、Quarkus の native 対応がコンテナサイズ面で最も有利です。

### 再現手順（コンテナ）

```bash
# Quarkus native
mvn -pl order-quarkus -am package -DskipTests \
  -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
docker build -f Dockerfile.quarkus-native -t order-quarkus-native:min .

# Quarkus JVM
mvn -pl order-quarkus -am package -DskipTests
docker build -f Dockerfile.quarkus-jvm -t order-quarkus-jvm:min order-quarkus/target/quarkus-app

# Open Liberty
cd order-liberty && mvn liberty:create liberty:install-feature liberty:deploy
docker build -f Dockerfile.liberty -t order-liberty:min target/liberty

# WildFly（slim）
mvn -pl order-wildfly -Pslim package -DskipTests
docker build -f Dockerfile.wildfly -t order-wildfly:min target
```
