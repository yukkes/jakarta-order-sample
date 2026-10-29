# Spring Boot との機能差を埋める Jakarta / MicroProfile 標準の検討

対象: `order-sample`（Quarkus 3.40 / Open Liberty 26 / WildFly 42 / GlassFish 8.0.5）
検討日: 2026-10-29

## 結論

Spring Boot の **AutoConfiguration / Starter / DevTools** は Jakarta EE の仕様だけでは再現できません。
一方で、**アプリケーションのプログラミングモデル**と**運用エンドポイント**は、Jakarta EE 11 と
MicroProfile の標準 API でかなり近づけられます。

優先度は次の 3 段階です。

| フェーズ | 内容 | 期間目安 | 全 4 基盤で可能か |
|---|---|---:|---|
| **Phase 1** | CDI Startup イベント、JPA 監査、CDI トランザクションイベント、CDI インターセプタ、RFC 9457 Problem Details | 2〜3 日 | ○ |
| **Phase 2** | MicroProfile Health、MicroProfile Config | 1 週間 | ○（Config は依存追加の判断が必要） |
| **Phase 3** | Jakarta Security / MP JWT、Metrics、Fault Tolerance、Telemetry | 1〜2 週間 | △（基盤差あり） |

**最初にやるなら Phase 1** です。特に CDI Startup イベントによる初期データ投入の共通化は、
現在 4 つあるアダプタ側の Seeder を 1 つに減らせるため、効果が大きくリスクが低い変更です。

## 実装状況（2026-10-29）

Phase 1 は実装済みです。

- `StartupDataSeeder`（`order-core`）に Seeder を共通化し、アダプタ側の 4 クラスを削除
- `Auditable`（`order-domain`）で `created_at` / `updated_at` を共通化
- `OrderConfirmedEvent` / `OrderCancelledEvent` と `OrderAuditListener` を追加
- `@Logged` / `LoggedInterceptor` を `OrderService` に適用
- RFC 9457 `ProblemDetail` を `Accept: application/problem+json` で返すように追加
- `PageQuery` / `PageResponse` レコードで一覧 API をページング（`sort=field,asc|desc` 対応）

Quarkus のテスト 23 件に加え、Liberty / WildFly / GlassFish でも起動・初期データ・ページング / ソート・Problem Details・認証・バッチを確認済みです。

---

## 1. 前提: Spring Boot の何に近づけるのか

Spring Boot の便利さは、大きく 3 層に分かれます。

1. **プログラミングモデル**: DI、`@Transactional`、`@Valid`、`@Scheduled`、`@Async`、AOP、イベント
2. **運用機能**: Health、Metrics、Tracing、外部化設定、ログ、OpenAPI
3. **開発体験**: Starter、AutoConfiguration、DevTools、Test slices、埋め込みサーバー

このうち 3 は Spring Boot 固有の仕組みで、Jakarta EE の仕様だけでは代替できません。
1 と 2 は、Jakarta EE 11 / MicroProfile 7.x の標準 API でかなりカバーできます。

このリポジトリは「`order-core` は Jakarta EE API だけ」という制約を持っているため、
MicroProfile を追加する場合は **制約を広げる判断**が必要です。以下の検討では、
Jakarta だけで完結するものと、MicroProfile が必要なものを分けて示します。

---

## 2. 現状の対応状況

| Spring Boot | このサンプルの現状 | 標準 API | 評価 |
|---|---|---|---|
| `@RestController` | JAX-RS `@Path` / `@GET` / `@POST` | Jakarta REST 4.0 | 済 |
| `@Service` / `@Repository` | CDI `@ApplicationScoped` + 手書き Repository | CDI 4.1 | 済 |
| `@Transactional` | `jakarta.transaction.Transactional` | Jakarta Transactions 2.0 | 済 |
| `@Valid` / Bean Validation | `jakarta.validation` + `ExceptionMapper` | Jakarta Validation 3.1 | 済 |
| `@ControllerAdvice` | JAX-RS `ExceptionMapper` | Jakarta REST 4.0 | 一部 |
| Spring Data JPA | `EntityManager` + JPQL | Jakarta Persistence 3.2 | 済 |
| Spring Data Repository | Jakarta Data 1.0 | Jakarta Data 1.0 | 別紙で検討中 |
| Spring Batch | Jakarta Batch 2.1 | Jakarta Batch 2.1 | 済 |
| `@Scheduled` | Quarkus: JBeret cron / 他: EJB `@Schedule` | EJB 4.0（Quarkus 非対応） | 基盤別 |
| OpenAPI | Quarkus / Liberty で公開 | MicroProfile OpenAPI | 一部 |
| Actuator Health | なし | MP Health | 未 |
| Actuator Metrics | なし | MP Metrics / Micrometer | 未 |
| `@Retry` / `@CircuitBreaker` | なし | MP Fault Tolerance | 未 |
| `@Cacheable` | なし | Jakarta Cache は EE 11 外 | 保留 |
| Spring Security | JAX-RS Filter + opaque token（Cookie / Bearer） | Jakarta Security 4.0 / MP JWT | 実装済み（標準の認証機構は未採用） |
| `@ConfigurationProperties` | `application.properties` / `server.xml` | MP Config | 一部 |
| `ApplicationRunner` | アダプタごとの Seeder | CDI 4.1 `Startup` イベント | 未（共通化可能） |
| `@TransactionalEventListener` | なし | CDI トランザクションイベント | 未 |
| `@Aspect` | なし | CDI Interceptor 2.2 | 未 |
| `@CreatedDate` / `@LastModifiedDate` | `createdAt` のみ | JPA `@PrePersist` / `@PreUpdate` | 一部 |
| `Pageable` / `Page` | 全件返却 | JPA + 独自 `PageResponse` | 未 |
| Starter / AutoConfiguration / DevTools | なし | 標準では不可能 | 非目標 |

---

## 3. Phase 1: Jakarta だけで完結する改善（実装済み）

以下の 3.1〜3.6 は実装済みです。コードは実装の要点を示しています。

### 3.1 CDI Startup イベントで初期データ投入を共通化

導入前は実行基盤ごとに Seeder が分かれていました。

- `QuarkusDataSeeder`（`io.quarkus.runtime.StartupEvent`）
- `LibertyDataSeeder` / `WildFlyDataSeeder` / `GlassFishDataSeeder`（EJB `@Startup`）

CDI 4.1 の `jakarta.enterprise.event.Startup` を使って、`order-core` の 1 クラスに共通化しました。

```java
@ApplicationScoped
public class StartupDataSeeder {

    @Inject
    private SeedService seedService;

    void onStart(@Observes Startup event) {
        seedService.seed();
    }
}
```

Quarkus 3.40.1 では `io.quarkus.runtime.StartupEvent` が
`jakarta.enterprise.event.Startup` を継承していることを確認済みです。
したがって、Quarkus でもこの observers はそのまま動きます。
Liberty / WildFly / GlassFish は CDI 4.1 実装なので仕様上は動作するはずですが、
JPA / Hibernate の初期化完了とイベント順序は各基盤で確認が必要です。

- 効果: アダプタ 4 クラスの削除、起動処理の共通化
- リスク: JPA / Hibernate の初期化完了とイベント順序が基盤ごとに異なる可能性。Quarkus のテストでは確認済み。他基盤は起動確認が必要
- Spring との対応: `ApplicationRunner` / `CommandLineRunner`

### 3.2 JPA の監査カラム

Spring Data の `@CreatedDate` / `@LastModifiedDate` に相当する処理は、JPA のコールバックで実装できます。

```java
@MappedSuperclass
public abstract class Auditable {

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- `Order` / `Invoice` が `Auditable` を継承し、重複する `createdAt` を削除
- スキーマは `drop-and-create` なので移行は容易
- Spring との対応: Spring Data JPA Auditing

### 3.3 コミット後の処理を CDI イベントで書く

Spring の `@TransactionalEventListener(phase = AFTER_COMMIT)` は、
CDI のトランザクションイベントで表現できます。

```java
public record OrderConfirmedEvent(Long orderId, String orderNo) {
}

// OrderService.confirmOrder(...) の最後
event.fire(new OrderConfirmedEvent(order.getId(), order.getOrderNo()));

// 監査ログ・通知など
void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderConfirmedEvent event) {
    // コミット後にだけ実行される
}
```

- 効果: 「注文確定後に監査ログを書く」「メールを送る」などを Service から分離できる
- 効果: ロールバック時には実行されないことを標準機能で保証できる
- Spring との対応: `@TransactionalEventListener`

### 3.4 CDI インターセプタで横断処理を分離

Spring AOP の `@Around` に相当する処理は、CDI Interceptor で実装できます。

```java
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LoggedInterceptor {

    @AroundInvoke
    Object log(InvocationContext context) throws Exception {
        long start = System.nanoTime();
        try {
            return context.proceed();
        } finally {
            // クラス名・メソッド名・実行時間をログ出力
        }
    }
}
```

- 用途: Service の実行時間ログ、監査、簡易メトリクス
- 注意: Metrics を本格的に取るなら Phase 3 の MP Metrics / Micrometer を使う
- Spring との対応: `@Aspect` / `@Around`

### 3.5 RFC 9457 Problem Details

Spring Boot 3 の `ProblemDetail` に相当するエラー表現は、Jakarta REST 4.0 には標準 API がありません。
小さな record と `ExceptionMapper` で実装しました。

```java
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance) {
}
```

既存の `ErrorResponse`（`status` / `error` / `message`）はそのままにし、
`Accept: application/problem+json` を指定したときだけ Problem Details を返すようにしました。
既定の `Accept: */*` や `application/json` では従来の形式を維持します。

- 効果: Spring Boot 3 のエラー形式に近づく。RFC 9457 は IETF 標準
- リスク: 既存クライアントとの互換性。`ErrorResponse` を拡張メンバーとして残すか、バージョニングする
- Spring との対応: `ProblemDetail` / `@ControllerAdvice`

### 3.6 ページング

Spring Data の `Pageable` / `Page` に相当する機能は、JPA の `setFirstResult` / `setMaxResults` で
Jakarta だけでも実装できます。`PageQuery` / `PageResponse` の 2 つの record で実装しました。

```java
public record PageQuery(int page, int size, List<SortOrder> sort) {
    public long offset() {
        return (long) page * size;
    }
}

public record SortOrder(String field, Direction direction) {
    public enum Direction { ASC, DESC }
}

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious) {
}
```

```java
@GET
@Path("/orders")
public PageResponse<OrderResponse> list(
        @QueryParam("status") String status,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("20") int size,
        @QueryParam("sort") List<String> sort) {
    return orderService.listOrders(status, PageQuery.of(page, size, sort, SORT));
}
```

- 効果: 一覧 API が本番向きになる。Spring Data の `Page` に近いレスポンス
- リスク: 既存の「全件返却」API の契約変更。`/api/orders` のレスポンスを配列からオブジェクトに変える場合はバージョニング
- ソートは `sort=field,asc|desc` で指定でき、項目は `SortSpec` のホワイトリストで検証します
- Spring との対応: `Pageable` / `Page`

---

## 4. Phase 2: MicroProfile で運用機能を足す

MicroProfile は Jakarta EE の外ですが、Quarkus / Liberty / WildFly / GlassFish の多くで使えます。
ただし **`order-core` の「Jakarta EE API だけ」制約を広げる**ため、アダプタ側に置くか、
プロジェクト全体の依存方針を変えるかの判断が必要です。

### 4.1 MicroProfile Health

Spring Boot Actuator の `/actuator/health` に最も近い標準です。
GlassFish 8.0.5 も MP Health 4.0.1 をサポートしているため、4 基盤で揃えられます。

- Quarkus: `quarkus-smallrye-health`
- Liberty: `mpHealth-4.0`
- WildFly: `microprofile-health` subsystem
- GlassFish: 標準搭載（MP Health 4.0.1）

```java
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("order-service");
    }
}
```

DB の readiness チェックは `EntityManager` で `select 1` を実行します。
エンドポイントのパスは Quarkus が `/q/health`、Liberty / WildFly / GlassFish が `/health` です。

### 4.2 MicroProfile Config

Spring の `@Value` / `@ConfigurationProperties` に近い機能です。
4 基盤とも対応しています（GlassFish 8.0.5 は MP Config 3.1.2）。

```java
@Inject
@ConfigProperty(name = "order.batch.page-size", defaultValue = "5")
int pageSize;
```

現状は `application.properties`（Quarkus）と `server.xml`（Liberty）に設定が分散しているため、
バッチのページサイズ、cron、DB 接続情報などを整理できます。
ただし `order-core` に MP Config API を入れると「Jakarta EE API だけ」ではなくなるため、
アダプタ側の Bean で設定を読むのが無難です。

### 4.3 ページングの標準化（Jakarta Data を使う場合）

Jakarta Data を採用する場合は、`PageRequest` / `Page` がそのまま使えます。
ただし Jakarta Data 自体の導入判断は [jakarta-data-assessment.md](jakarta-data-assessment.md) のとおりです。
Jakarta Data を使わない場合は、3.6 の独自 `PageResponse` で十分です。

---

## 5. Phase 3: セキュリティと可観測性（基盤差あり）

### 5.1 Jakarta Security 4.0

認証・認可は、Jakarta REST の `ContainerRequestFilter` + opaque token（Cookie / Bearer）で実装済みです。
JAX-RS リソースには `@RequireRole` を付け、`RoleAuthorizationFilter` でロールを検査するため、
Quarkus / Liberty / WildFly / GlassFish で同じコードが動きます。
`@RolesAllowed` は GlassFish でコンテナ認証を起動してしまうため、JAX-RS リソースには付けません。

以下は、標準の Jakarta Security 認証機構に寄せる場合の検討メモです。

Spring Security に最も近い Jakarta 標準です。

- `@RolesAllowed` / `@PermitAll` / `@DenyAll`
- `SecurityContext`
- `@BasicAuthenticationMechanismDefinition`
- `@DatabaseIdentityStoreDefinition` / `@PasswordIdentityStore`

ただし **Quarkus は Jakarta Security の全 API をサポートしていません**。
Quarkus は `@RolesAllowed` と `SecurityIdentity` を独自の Security 拡張で提供し、
`HttpAuthenticationMechanism` などの SPI は無視されます。
Liberty / WildFly / GlassFish は Jakarta Security 4.0 を実装しています。

したがって、4 基盤で同じセキュリティ設定を共有するのは現実的ではありません。
Quarkus だけ Quarkus Security、他は Jakarta Security というアダプタ分離が必要です。

### 5.2 MicroProfile JWT

4 基盤とも MP JWT をサポートしています（GlassFish 8.0.5 は MP JWT 2.2）。
トークンベースの認証を標準化できますが、Keycloak などの issuer が必要です。
デモとしては `quarkus-oidc` / Liberty `jwt-1.0` / WildFly `microprofile-jwt` の設定差が大きいため、
Phase 2 の Health より難易度は高いです。

### 5.3 Metrics / Fault Tolerance / Telemetry

| 機能 | Quarkus | Liberty | WildFly | GlassFish 8.0.5 |
|---|---|---|---|---|
| MP Health | ○ | ○ | ○ | ○ |
| MP Config | ○ | ○ | ○ | ○ |
| MP Metrics | ○ | ○ | ○ | × |
| MP Fault Tolerance | ○ | ○ | ○ | × |
| MP Telemetry | ○ | ○ | ○ | × |
| MP JWT | ○ | ○ | ○ | ○ |
| Jakarta Security 4.0 | △（部分） | ○ | ○ | ○ |

GlassFish 8.0.5 は MP Config / JWT / REST Client / Health のみをサポートします。
Metrics / Fault Tolerance / Telemetry を全基盤で揃えることはできないため、
これらを入れる場合は「Quarkus / Liberty / WildFly 向けの運用機能」と割り切る必要があります。

- **Metrics**: Spring Actuator / Micrometer に近い。Quarkus は Micrometer、Liberty は `mpMetrics-5.1`
- **Fault Tolerance**: `@Retry` / `@Timeout` / `@CircuitBreaker` / `@Fallback`。Spring Retry / Resilience4j に近い
- **Telemetry**: OpenTelemetry。Spring Boot 3 の Observability に近い

---

## 6. 採用しない / 保留するもの

| 項目 | 理由 |
|---|---|
| Starter / AutoConfiguration / `@ConditionalOn...` | Spring Boot 固有。Jakarta 標準では代替できない |
| DevTools / Test slices | フレームワーク固有。Quarkus Dev Mode / `@QuarkusTest` で代替 |
| `@Scheduled` の標準アノテーション | Jakarta EE にない。EJB `@Schedule` は Quarkus 非対応。現状の JBeret cron + EJB の併用を維持 |
| `@Async`（Jakarta Concurrency） | 標準はあるが Quarkus の対応が部分的。Phase 3 以降 |
| `@Cacheable` | Jakarta Cache は EE 11 に含まれない。JCache は実装依存 |
| Jakarta Mail | GlassFish は Full Platform のみ。SMTP 前提のデモが必要 |
| Jakarta Messaging | ブローカー（Artemis / OpenMQ）前提。サンプルの趣旨から外れる |
| Jakarta Data | [jakarta-data-assessment.md](jakarta-data-assessment.md) のとおり、現時点では見送り |
| `@Service` / `@Repository` stereotype | 見た目は Spring に近づくが、実質的な価値は薄い。Jakarta Data の `@Repository` と紛らわしい |
| Arquillian / Testcontainers | 標準的な EE テストは重い。現状の Quarkus テストを維持 |

---

## 7. 推奨ロードマップ

### Phase 1（実装済み）

1. `StartupDataSeeder` を `order-core` に追加し、4 つのアダプタ Seeder を削除
2. `Auditable` を追加し、`Order` / `Invoice` に適用
3. `OrderConfirmedEvent` / `OrderCancelledEvent` と監査リスナーを追加
4. `@Logged` インターセプタを追加し、`OrderService` に適用
5. RFC 9457 `ProblemDetail` を追加（既存 `ErrorResponse` は維持）
6. 一覧 API に `PageQuery` / `PageResponse` レコードと `sort` を追加
7. `OrderApiTest` / `BatchJobTest` を実行し、Quarkus で回帰確認

### Phase 2（1 週間）

8. MP Health を追加（全 4 基盤、DB readiness 含む）
9. MP Config でバッチ設定を外部化（アダプタ側）

### Phase 3（1〜2 週間）

10. Jakarta Security / MP JWT への移行を検討（現在は JAX-RS Filter + opaque token）
11. MP JWT を追加（Keycloak などの issuer を用意）
12. Metrics / Fault Tolerance / Telemetry を Quarkus / Liberty / WildFly 向けに追加

---

## 8. 参考

- [Jakarta EE 11 Specifications](https://jakarta.ee/specifications/)
- [Jakarta Contexts and Dependency Injection 4.1](https://jakarta.ee/specifications/cdi/4.1/)
- [Jakarta RESTful Web Services 4.0](https://jakarta.ee/specifications/restful-ws/4.0/)
- [Jakarta Persistence 3.2](https://jakarta.ee/specifications/persistence/3.2/)
- [Jakarta Security 4.0](https://jakarta.ee/specifications/security/4.0/)
- [MicroProfile 7.1](https://microprofile.io/)
- [MicroProfile Health](https://microprofile.io/specifications/health/)
- [MicroProfile Config](https://microprofile.io/specifications/config/)
- [MicroProfile Fault Tolerance](https://microprofile.io/specifications/fault-tolerance/)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [GlassFish 8 Release Notes（MicroProfile 対応表）](https://glassfish.org/docs/latest/release-notes.html)
- [Open Liberty Features](https://openliberty.io/docs/latest/reference/feature/feature-overview.html)

---

## 付録: 検証メモ

- `io.quarkus.runtime.StartupEvent` が `jakarta.enterprise.event.Startup` を継承していることを
  Quarkus 3.40.1 の `quarkus-core` で確認済み。CDI 4.1 の Startup イベント observer は Quarkus でも動作する見込み。
- GlassFish 8.0.5 の MicroProfile 対応は Config 3.1.2 / JWT 2.2 / REST Client 4.0 / Health 4.0.1。
  Metrics / Fault Tolerance / Telemetry は含まれない。
- Quarkus は Jakarta Security の全 API をサポートしない。`@RolesAllowed` は使えるが、
  `HttpAuthenticationMechanism` などの SPI は Quarkus Security の独自実装に置き換える必要がある。
- 本ドキュメントは調査・設計の検討に加え、Phase 1 の実装結果を反映しています。
  Quarkus のテスト 23 件に加え、Liberty / WildFly / GlassFish でも手動スモーク確認済みです。
