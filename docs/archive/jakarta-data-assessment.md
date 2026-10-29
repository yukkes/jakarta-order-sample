# Jakarta Data 1.0 導入検討

対象: `order-sample`（Quarkus 3.40 / Open Liberty 26 / WildFly 42 / GlassFish 8.0.5）
検討日: 2026-10-29

## 結論

**`order-core` への Jakarta Data 1.0 の全面導入は、現時点では見送ることを推奨します。**
標準 API としての価値は高いものの、このサンプルが守っている次の 3 つの前提と衝突します。

1. **Jakarta Data 1.0 の標準のライフサイクルは stateless 前提**で、`EntityManager` の managed entity + dirty checking を前提にした現在の Service 実装と意味論が異なる（Hibernate 実装は `StatelessSession` を使い、managed entity を扱う stateful repository は 1.1 で追加予定）
2. **実装方式が実行基盤ごとに異なる**ため、Hibernate の生成実装を `order-core` の JAR に入れると Liberty / GlassFish で動かない
3. **`order-core` が Quarkus 非依存**という構成を壊す（Hibernate の annotation processor が必要）

次アクションとしては、**`order-quarkus` アダプタ内に限定した読み取り系の PoC**（0.5〜1 人日）を行い、
コンパイル時クエリ検証と記述量削減の効果だけを測るのが現実的です。
本採用は、対象を Hibernate 系 2 基盤（Quarkus + WildFly）に絞るか、
Jakarta Data 1.1 の stateful repository を待つか、ports & adapters 化を受け入れるかの判断後に行います。

---

## 1. Jakarta Data 1.0 の位置づけ

Jakarta EE 11 で追加されたリポジトリ API です。

- `@Repository` を付けたインターフェースに `CrudRepository` / `BasicRepository` を継承させる
- `@Find`（パラメータ名ベース）や `@Query`（JDQL。Hibernate では HQL のサブセット）でクエリを宣言する
- `Limit` / `PageRequest` / `Sort` / `Order` でページングと並び替えを表現する
- Hibernate 実装では **annotation processor がコンパイル時に実装クラスを生成**し、クエリを型検査する

このサンプルにとっての利点は次の 2 点です。

- Repository のボイラープレート（4 クラス、約 200 行）をインターフェースに置き換えられる
- JPQL の typo や型の不整合を、実行時ではなくコンパイル時に検出できる

一方で、**Jakarta Data 1.0 の標準のリポジトリ操作は stateless 前提**です。
Hibernate 実装は `StatelessSession` を使い、managed entity を扱う stateful repository は 1.1 で追加予定です
（1.1 は現在 M4、Jakarta EE 12 向け）。
`BasicRepository` の API にも `count()` や `existsById()` はなく、必要なら `@Query` を書きます。

---

## 2. 実行基盤ごとの対応状況（2026-10 時点）

4 基盤とも Jakarta Data 1.0 自体には対応していますが、**実装の置き場所が違います**。

| ランタイム | バージョン | 実装方式 | このリポジトリでの設定 |
|---|---|---|---|
| Quarkus | 3.40.1 | Hibernate Data Repositories。`quarkus-data-processor` で**コンパイル時に実装生成** | `order-quarkus` に processor 設定を追加 |
| Open Liberty | 26.0.0.10 | `data-1.0` の**内蔵プロバイダ**。Jakarta Data を JPA に変換して実行時に実装 | `jakartaee-11.0` に含まれるため `server.xml` 変更不要 |
| WildFly | 42 | Hibernate Data Repositories（`jakarta-data` subsystem）。**ビルド時に実装生成** | Hibernate のバージョン整合が必要 |
| GlassFish | 8.0.5 | JNoSQL の JPA バックエンドによる**実行時プロバイダ** | 8.0.5 で JPA entity に対応 |

補足:

- Liberty の `jakartaee-11.0` は `webProfile-11.0` を経由して `data-1.0` を有効化します。既存の `server.xml` は変更不要です。
- WildFly の Jakarta Data は WildFly 40 以降で利用できます。実装は Hibernate Data Repositories なので、**アプリ側で annotation processor を実行して生成したクラスを WAR に含める**必要があります。
- GlassFish 8.0.5 は JPA entity と NoSQL entity の両方で Data repository をサポートしますが、Hibernate の生成実装は使いません。

この違いが、後述の「1 つの core JAR を 4 基盤で共有できない」問題の根本です。

---

## 3. 現行コードとのギャップ

### 3.1 Repository の対応関係

| 現行 (`order-core`) | Jakarta Data での置き換え | 注意点 |
|---|---|---|
| `OrderRepository.findById` | `CrudRepository.findById` | stateless session の `get`。返る entity は detached |
| `OrderRepository.findPage(status, offset, limit)` | `@Query(...) + Limit` | `Limit.startAt()` は **1 始まり**。`Limit.range(offset + 1, offset + pageSize)` に変換 |
| `OrderRepository.persist` / `flush` | `insert` / `save` | `flush()` は Jakarta Data の API にない |
| `ProductRepository.count` | `@Query("select count(p) from Product p")` | `BasicRepository` に `count()` がない |
| `InvoiceRepository.existsByInvoiceNo` | `@Query("select count(i) ...")` | `existsById` もないため count で代用 |
| `CustomerRepository.findByCode` | `@Find Optional<Customer> findByCode(@By("code") String code)` | 問題なし（PoC で確認済み） |

### 3.2 managed entity 前提の処理が壊れる

現在の Service は、トランザクション内で取得した entity を変更し、dirty checking と `flush()` に任せています。
Hibernate 実装の Jakarta Data repository は stateless session なので、このパターンはそのままでは動きません。

- `OrderService.confirmOrder` / `cancelOrder`
  - `order.setStatus(...)` や `product.setStock(...)` の後に、明示的に `save(order)` / `save(product)` が必要
- `OrderService.createOrder` / `SeedService.seed`
  - `Order.lines` の `cascade = ALL` は stateless session では効かない。`OrderLine` を 1 件ずつ `insert` する必要がある
- `OrderService.flushOrConflict`
  - 楽観ロックの例外が `OptimisticLockException` から `OptimisticLockingFailureException`（Jakarta Data）に変わる
- `toResponse`
  - `order.getLines()`、`line.getProduct()`、`invoice.getCustomer()` などの LAZY 関連を fetch join で取得しないと、Service のトランザクション外で失敗する
  - `findById` は fetch join しないため、`@Query("select o from Order o join fetch o.lines l join fetch l.product where o.id = ?1")` のような専用メソッドが必要
- バッチの `findForMonth`
  - `Limit` への置き換え自体は可能だが、offset の 1 始まり変換を間違えると checkpoint がずれる

### 3.3 移植性が壊れる（最重要）

PoC で生成された実装クラスを確認したところ、次のようになっていました。

```java
@Dependent
@Generated("org.hibernate.processor.HibernateProcessor")
public class OrderDataRepository_ implements OrderDataRepository {

    protected StatelessSession session;

    @Inject
    public OrderDataRepository_(StatelessSession session) {
        this.session = session;
    }
    // ...
}
```

つまり、生成実装は **`org.hibernate.StatelessSession` に直接依存**し、CDI の `@Dependent` Bean として WAR に含まれます。

- Quarkus / WildFly: Hibernate があるので動く
- Liberty: EclipseLink ベース。`StatelessSession` が存在せず、CDI がこの Bean を発見すると起動時または最初の利用時に失敗する
- GlassFish: JNoSQL の実行時プロバイダを使うため、Hibernate の生成実装は不要かつ邪魔になる

`order-core` は `META-INF/beans.xml` を持つ Bean archive なので、生成実装を core の JAR に入れると
Liberty / GlassFish でも確実にスキャン対象になります。除外するには WAR 作成時にクラスを削るなどの
ビルドプロファイルが必要になり、サンプルの「同じ core を 4 基盤で動かす」という前提が崩れます。

さらに、生成実装を core に置くには `order-core` の POM に `quarkus-data-processor` を追加する必要があり、
「`order-core` は Quarkus に依存しない」という現在の設計とも矛盾します。

加えて、Liberty の内蔵プロバイダは Jakarta Data を JPA に委譲するため、同じインターフェースでも
Hibernate の stateless 実装とは entity の管理状態や lazy loading の挙動が揃わない可能性があります（要検証）。
「API が同じ」であることと「振る舞いが同じ」であることは別問題です。

---

## 4. 選択肢

| 案 | 内容 | 工数（目安） | リスク | 4 基盤維持 | 評価 |
|---|---|---:|---|---|---|
| **A. 現状維持** | 何もしない。再評価条件だけ決めておく | 0 | 低 | ○ | **推奨** |
| **B. Quarkus 限定 PoC** | `order-quarkus` に読み取り系だけ Jakarta Data を追加 | 0.5〜1 人日 | 中 | ○ | **次のアクション** |
| **C. ports & adapters** | core に plain interface、各基盤が実装を差し替え | 2〜3 人日 | 中〜高 | ○ | 将来の選択肢 |
| **D. Hibernate 系に寄せる** | core を Hibernate 依存にし、Quarkus + WildFly のみ対象 | 3〜5 人日 | 高 | × | 非推奨 |

### A. 現状維持

`EntityManager` + JPQL の Repository は、4 基盤で同じコードが動くという意味で現在のサンプルの目的に合っています。
Jakarta Data の便益（クエリのコンパイル時検証、記述量削減）より、移植性と managed entity の扱いやすさを優先します。

### B. Quarkus 限定 PoC（推奨する次アクション）

`order-core` には手を入れず、`order-quarkus` に読み取り専用の repository を追加します。

- 対象は `ProductRepository` / `CustomerRepository` のような単純な参照系から始める
- `OrderService` は core の具象クラスを inject しているため、PoC では専用の Resource / Service を Quarkus 側に置く
- `quarkus-data-processor` を `order-quarkus` の `maven-compiler-plugin` に設定する
- `OrderApiTest` / `BatchJobTest` がそのまま通ることを確認する

この PoC の目的は「本採用」ではなく、次の 3 点の実測です。

1. クエリのコンパイル時検証で、どの程度のミスを早期に拾えるか
2. lazy 関連を fetch join でどこまで素直に書けるか
3. 同時実行時に `StatelessSession` のスコープが問題にならないか

### C. ports & adapters

`order-core` の Repository を具象クラスからインターフェースに変え、JPA 実装を core に残します。
そのうえで Quarkus 側だけ Jakarta Data 実装を差し替えます。

- core: `OrderRepository`（plain interface）、`JpaOrderRepository`（現行実装）
- Quarkus: `@Repository` を付けた `OrderDataRepository extends CrudRepository<Order, Long>, OrderRepository`
- Liberty / WildFly / GlassFish: core の JPA 実装をそのまま使う
- CDI の解決は `@Alternative` + `@Priority` か、Quarkus のプロファイルで切り替える

PoC では、`@Repository` インターフェースが core の interface を extends した場合に、
生成クラスがその interface を implements することを確認済みです（`ProductPort` で検証）。
CDI の bean type 解決（core の interface 型で inject できるか）は未検証です。
また Service 側の注入点をインターフェースに変えるリファクタリングが必要で、
サンプルの学習用コードとしては複雑さが増します。

### D. Hibernate 系に寄せる

`order-core` を Hibernate 依存にし、Quarkus と WildFly だけで運用します。
Liberty / GlassFish アダプタを外すなら最も自然ですが、このリポジトリの「4 基盤比較」という価値がなくなります。

---

## 5. 導入する場合のコード例（コンパイル検証済み）

Quarkus 3.40.1 / Hibernate ORM 7.4.9.Final / `jakarta.data-api` 1.0.1 で、
以下のインターフェースが実際にコンパイルできることを確認しました。

```java
@Repository
public interface OrderDataRepository extends CrudRepository<Order, Long> {

    @Query("select o from Order o where o.status = ?1 order by o.id desc")
    List<Order> findByStatus(OrderStatus status);

    @Query("select o from Order o order by o.id desc")
    List<Order> findAllNewestFirst();

    @Query("select o from Order o where o.orderDate >= ?1 and o.orderDate < ?2 order by o.id")
    List<Order> findForMonth(LocalDate from, LocalDate to, Limit limit);

    @Query("select count(o) from Order o where o.status = ?1")
    long countByStatus(OrderStatus status);

    @Query("select o from Order o join fetch o.lines l join fetch l.product where o.id = ?1")
    Order findWithLines(Long id);
}
```

バッチの offset は次のように変換します。

```java
// 現行: findForMonth(month, offset, pageSize)
List<Order> page = orderRepository.findForMonth(
        month.atDay(1),
        month.plusMonths(1).atDay(1),
        Limit.range(offset + 1, offset + pageSize)); // startAt は 1 始まり
```

`Limit` は生成実装で `setFirstResult(limit.startAt() - 1)` に変換されることも確認済みです。

---

## 6. 判定基準（Go / No-Go）

**Go に進めてよい条件**

- 対象ランタイムを Quarkus + WildFly（Hibernate 系）に限定してよい
- Jakarta Data 1.1 の stateful repository を待てる、または stateless 前提に Service を書き換えてよい
- `order-core` の Quarkus 非依存を諦めてよい、または ports & adapters の複雑さを受け入れられる
- lazy 関連を fetch join で明示的に取得する設計に納得できる

**No-Go とすべき条件**

- 4 基盤で同一の `order-core` JAR を維持したい
- managed entity の dirty checking を残したい
- `order-core` を Jakarta EE API だけに保ちたい

---

## 7. 参考

- [Jakarta Data 1.0 Specification](https://jakarta.ee/specifications/data/1.0/)
- [Jakarta Data 1.1 (Under Development)](https://jakarta.ee/specifications/data/1.1/) — stateful repository は 1.1 で追加予定
- [Quarkus - Using Hibernate ORM and Jakarta Persistence (Jakarta Data)](https://quarkus.io/guides/hibernate-orm#jakarta-data)
- [Quarkus Data Hibernate (experimental)](https://quarkus.io/guides/quarkus-data-hibernate)
- [Hibernate Data Repositories](https://hibernate.org/repositories/)
- [Open Liberty - Jakarta Data 1.0 feature](https://openliberty.io/docs/latest/reference/feature/data-1.0.html)
- [Open Liberty - Limitations of the Built-in Jakarta Data Provider](https://openliberty.io/docs/latest/limitations-of-the-built-in-jakarta-data-provider.html)
- [WildFly Developer Guide - Jakarta Data Reference](https://docs.wildfly.org/40/Developer_Guide.html)
- [GlassFish 8 Release Notes](https://glassfish.org/docs/latest/release-notes.html)

---

## 付録: 検証ログ

- 検証環境: Java 21、Quarkus BOM 3.40.1、Hibernate ORM 7.4.9.Final、`jakarta.data-api` 1.0.1
- 検証方法: 一時 Maven プロジェクトに `order-domain` の entity をコピーし、`quarkus-data-processor` を
  `maven-compiler-plugin` の annotation processor に指定して `mvn compile`
- 確認できたこと:
  - `@Query` の JPQL（`join fetch`、`count`、位置パラメータ）がコンパイル時に検証される
  - `Limit` パラメータが `setFirstResult(startAt - 1)` / `setMaxResults` に変換される
  - 生成クラスは `@Dependent` で、`org.hibernate.StatelessSession` をコンストラクタ注入する
  - core の plain interface を `@Repository` インターフェースが extends しても生成できる
- 未検証:
  - Liberty / WildFly / GlassFish での実行時動作（各プロバイダのドキュメントに基づく推測）
  - 同時実行時の `StatelessSession` のスコープ
  - fetch join とページングの組み合わせ（コレクション fetch + `Limit` は避ける前提）
