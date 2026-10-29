# Jakarta EE ランタイム性能比較

同一の `order-core` / `order-api` / `order-domain` を Quarkus / Open Liberty / WildFly / GlassFish で動かし、
同じ条件で起動時間・メモリ・スループットを測定しました。

## 条件

- 16 vCPU（8 物理コア / SMT）、メモリ 8 GB、WSL2
- Java 21
- PostgreSQL 17（Docker、各起動前にボリュームを初期化）
- サーバーとクライアントの CPU 固定なし
- 起動時間: プロセス起動から `GET /api/products` が 200 を返すまで（3回の中央値）
- スループット: `ab -n 10000 -c 50 -k` でウォームアップ後、`ab -n 20000 -c 100 -k` を3回実行した中央値
- メモリ: `ps` の RSS（起動直後 / 負荷後）
- レスポンス: HTTP 200、308 bytes、商品4件の JSON

## 結果

| ランタイム | 起動時間 | 起動直後 RSS | 負荷後 RSS | スループット c=100 | p99 |
|---|---:|---:|---:|---:|---:|
| **Quarkus 3.40** | **2.65 秒** | **292 MB** | **660 MB** | **18,200 req/s** | **12 ms** |
| Open Liberty 26 | 7.68 秒 | 588 MB | 821 MB | 8,700 req/s | 25 ms |
| WildFly 42 | 6.42 秒 | 652 MB | 755 MB | 8,000 req/s | 23 ms |
| GlassFish 8 | 9.73 秒 | 591 MB | 675 MB | 6,600 req/s | 17 ms |

## 補足

- Quarkus が起動・スループットともに最も優位
- Liberty と WildFly は近い性能。WildFly のほうが起動は速い
- GlassFish は起動が最も遅く、スループットも最も低い
- RSS は JVM のネイティブメモリや Metaspace を含む。ヒープ使用量とは異なる
- `ab` の CPU 使用率は約 0.5 コアで、クライアントがボトルネックではない
- 数値は同一ホスト上のマイクロベンチマークであり、本番性能を保証するものではない

## 実行アダプタ

- `order-quarkus`: Quarkus
- `order-liberty`: Open Liberty
- `order-wildfly`: WildFly
- `order-glassfish`: GlassFish
