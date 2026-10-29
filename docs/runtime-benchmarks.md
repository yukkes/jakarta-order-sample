# Jakarta EE ランタイム性能比較

同一の `order-core` / `order-api` / `order-domain` を Quarkus / Open Liberty / WildFly / GlassFish で動かし、
同じ条件で起動時間・メモリ・スループットを測定しました。

## 条件

- 16 vCPU（8 物理コア / SMT）、メモリ 8 GB、WSL2
- Java 21（Quarkus native のみ Mandrel 25.0.4.1）
- PostgreSQL 17（Docker、各測定の前にスキーマを作り直す）
- サーバーとクライアントの CPU 固定なし
- **スループット: `wrk -t4 -c100 -d10s --latency` を 3 回、中央値**
  - `ab` は 20k req/s 付近でクライアント側がボトルネックになるため、wrk に変更した
  - keep-alive 有効。認証は `/api/auth/login` のアクセストークン（JWT）を `Authorization: Bearer` で送る
- 起動時間: プロセス / コンテナ起動から `/api/auth/status` が 200 を返すまで（3 回の中央値）
- メモリ: 起動直後の RSS（Liberty / Quarkus は `ps`、WildFly / GlassFish は `docker stats`）
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
| **Quarkus 3.40（JVM）** | 3.45 秒 | 277 MB | **180,032** | **149,534** | **16,921** |
| **Quarkus 3.40（native）** | **1.18 秒** | **137 MB** | 85,811 | 69,535 | 10,218 |
| Open Liberty 26 | **5.73 秒** | 640 MB | 53,916 | 58,012 | 12,634 |
| WildFly 42（Jackson / slim） | **6.53 秒** | 592 MB | 68,605 | 63,147 | 13,194 |
| GlassFish 8 | 11.14 秒 | 573 MB | 31,884 | 26,675 | 3,509 |

p99（中央値）:

| ランタイム | status | me | products |
|---|---:|---:|---:|
| Quarkus JVM | 3.7 ms | 4.0 ms | 12.8 ms |
| Quarkus native | 11.7 ms | 12.6 ms | 36.0 ms |
| Open Liberty | 4.6 ms | 5.5 ms | 16.8 ms |
| WildFly | 12.0 ms | 12.2 ms | 19.0 ms |
| GlassFish | 5.6 ms | 6.3 ms | 31.7 ms |

## 考察

- **JSON-P のプロバイダ解決が認証の最大コストだった**。`Json.createReader` は呼び出しごとに
  ServiceLoader でプロバイダを探すため、JWT 検証が 1 リクエストあたり 70〜80 µs かかっていた。
  プロバイダを 1 回だけ解決して使い回すと、`me` は Quarkus 11.8k → **149.5k**、
  Liberty 13.9k → 58.0k、WildFly 29.5k → 63.1k になる
- **HTTP / JSON 層**（`/api/auth/status`）は Quarkus（JVM）が最も速い（180k）。native は 86k で、
  小さな JSON では JIT の最適化が AOT を上回る
- **DB クエリを含む `products`** は Quarkus が 16.9k で最も速い。WildFly 13.2k、Liberty 12.6k、
  native 10.2k、GlassFish 3.5k。プール 100 と、Liberty は EclipseLink の L2 キャッシュ無効化が効いている
- **native image** は起動 1.18 秒・137 MB で JVM の 3.45 秒・277 MB より明確に優位。
  スループットは JVM より低い（status 86k 対 180k、products 10.2k 対 16.9k）
- GlassFish は products が最も低い（3.5k）。プール・ステートメントキャッシュ・クエリキャッシュ・
  トランザクション省略のいずれも大きな改善にならず、EclipseLink のリクエスト処理が支配的

## 起動時間とメモリ

| ランタイム | 起動 | 起動直後 RSS | 備考 |
|---|---:|---:|---|
| Quarkus native | **1.18 秒** | **137 MB** | Mandrel 25.0.4.1、バイナリ 104 MB |
| Quarkus JVM | 3.45 秒 | 277 MB | |
| WildFly 42 | **6.53 秒** | 592 MB | Galleon で必要な layer だけを provisioning（`-Pslim`） |
| GlassFish 8 | 11.14 秒 | 573 MB | Docker コンテナ |
| Open Liberty 26 | **5.73 秒** | 640 MB | feature を最小構成に変更（`server start` から測定） |

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
  wrk で測り直すと、Quarkus の HTTP 層は 144k req/s まで出る
- 以前の数値は「認証方式が opaque（DB 照合あり）」の時代のものでもある。現在の数値と直接比較しないこと
- 数値は同一ホスト上のマイクロベンチマークであり、本番性能を保証するものではない

## native image の制約

**Jakarta Batch は native で動作しない。** 請求書バッチを開始すると
`QuarkusJobRepository.savePersistentData` で例外が発生し、プロセスが停止する。
REST API と画面（注文・商品・請求書・認証）は動作する。native を採用する場合は、
バッチを別プロセスに分けるか、JBeret の native 対応を待つ必要がある。

## 再現手順

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
