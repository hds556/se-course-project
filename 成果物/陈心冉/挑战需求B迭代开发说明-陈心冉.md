# 挑战需求B迭代开发说明

- 姓名：陈心冉
- 迭代：挑战需求B迭代
- 负责范围：卖家端「订单推进 / 卖家取消 / 两个订单查询入口」——卖家订单处理
- 代码工程：`se-course-project`
- 所在分支：`projectB`

---

## 一、我负责的具体需求内容

本次迭代我负责卖家侧的订单处理，对应需求条目 14207、14208、14209、14214：

| 需求编号 | 需求名称 | 需求要点 |
| --- | --- | --- |
| 14207 | 推进订单状态 | 五状态严格顺序推进：客户下单 → 商家确认 → 备货完成 → 开始发货 → 交易完成。一次只进一格，不能跳过、不能回退；交易完成是终态，不可撤销 |
| 14208 | 卖家取消订单 | 交易完成之前卖家都可以取消（**含「开始发货」**）；取消后按明细数量回补库存，售罄下架的商品随库存恢复在售；取消即终态 |
| 14209 | 查看商品订单记录 | 按**商品**维度查询涉及该商品的全部订单（不限订单状态），带该商品的订单笔数、累计件数、当前库存与状态，可分页 |
| 14214 | 查看客户购买历史 | 按**客户**维度查询该客户的全部订单，可在同一界面直接推进或取消；客户之间互相隔离 |

**范围边界（本次未做，属其他成员职责）**：

- 订单实体、订单明细表、下单与客户取消（14201～14205）由开发 A（范君豪）负责，本次只**引用**他的实体与仓储，不修改其业务代码；
- 商品发布 / 编辑 / 详情（14210～14212）由开发 C 负责；
- 商品状态机、手动下架、调库存、历史商品与旧交易机制拆除（14206 / 14213 / 14215）由开发 D 负责。

本次对订单表只写 `order.status` 一个字段，符合分工文档中「陈心冉仅写 status 字段」的约定。

---

## 二、已实现的功能点

> 阅读提示：本章只讲**这一次迭代**新增的内容。上一迭代已存在的文件（`admin.html`、`catalog.css`、`ShoppingService` 的卖家会话校验、`ProductRepository` 的库存回补）在本次均属**复用 / 追加**，已在文中标注「复用」「追加」，凡是上一次已有的能力不重复计入本次成果。

### 2.1 推进订单状态（14207）

1. 五状态**顺序推进**：每次操作只让状态前进一格，服务端按显式推进表判定下一步，不存在「一次跳两级」的接口形态。
2. **不可跳过**：接口允许显式指定目标状态 `{"to":"packed"}`，只要目标状态不是「当前状态的下一格」，一律 409 拒绝并给出「当前是什么、下一步只能是哪一个」的具体提示。
3. **不可回退**：目标状态等于当前状态（原地推进）或早于当前状态（回退）同样 409 拒绝。
4. **交易完成不可撤销**：`completed` 与 `cancelled` 均为终态，对其推进返回 409，其中「交易完成」的提示明确写出「交易完成不可撤销」。
5. **并发与重复点击安全**：状态写入用条件更新（`where id = ? and status = ?`），受影响行数为 0 即判定「状态已被其他操作变更」，不会因两次点击把状态推进两格。
6. 每次推进的响应都带回 `status / statusLabel / nextStatus / nextStatusLabel / advanceable / cancellable`，前端据此渲染「推进到「××」」按钮，不必自己维护状态机。

### 2.2 卖家取消订单（14208）

1. **交易完成前均可取消**，包含「开始发货」——这一点与客户侧（14204）不同：客户在「开始发货」后不再有取消入口，卖家仍可取消。
2. 取消**立即生效**，写入 `cancelled` 终态；「已取消」「交易完成」不可再次取消（分别提示「无需重复取消」「交易完成后不可取消」）。
3. **库存按明细数量逐项回补**，回补走既有商品更新接口 `ProductRepository.restoreStock`，不新增库存写入口：该方法同时处理「售罄下架（`depleted`）的商品在可售库存恢复为正后自动回到在售」，且不会碰手动下架（`withdrawn`）的商品。
4. 响应逐项列出回补结果（商品、数量、回补后库存与状态）与回补总件数，页面据此给出一句话结论。
5. **只回补一次**：先做状态条件更新，只有真正把状态改成「已取消」的那一次请求才回补库存，重复点击或并发请求不会把同一笔订单的库存回补两次。
6. 订单与明细均不物理删除，取消只改状态（订单留痕）。

### 2.3 查看商品订单记录（14209）

1. 按商品维度列出**涉及该商品的全部订单**，不限订单状态：进行中、交易完成、已取消都会出现在同一列表里。
2. 页面顶部给出该商品的汇总：涉及订单笔数、累计下单件数、当前库存、当前商品状态。
3. 订单行内明细中，**属于该商品的那一行加底色高亮并标「本商品」**，一眼能看出这一单里该商品买了几件。
4. 按下单时间倒序、服务端分页（默认每页 10 笔），与买家端「我的订单」的排序口径完全一致。
5. 每条订单直接带「推进到下一状态」「取消订单」两个动作入口，可以从商品维度就地处理。

### 2.4 查看客户购买历史（14214）

1. 按客户维度列出该客户的**全部订单**（含进行中 / 交易完成 / 已取消），不是只列终态记录。
2. 头部给出客户信息：用户名、电话、默认交易地点、注册时间、订单笔数。
3. **可在同一界面直接推进或取消**，与 14207 / 14208 共用同一条动作通道，不另起一套逻辑。
4. 客户之间互相隔离：查询条件是「指定的 customerId」，返回体里每笔订单也带 `customerName`，不会混入其他客户的订单。

---

## 三、实现思路与关键技术方案

### 3.1 数据边界：只为 `order.status` 负责

分工文档把订单表的所有权给了开发 A，把库存字段的所有权给了开发 D，我这一侧只被允许写 `order.status`。为了让这条约定在代码上也成立：

- **不修改**开发 A 的 `OrderEntity` / `OrderItemEntity` / `OrderStatus` / `OrderService` / `OrderController` / `OrderRepository` 任何一行；
- 为本领域单开 `SellerOrderRepository`（订单只读查询 + 一条状态条件更新）与 `SellerOrderItemRepository`（两个聚合查询）。同一实体存在两个仓储接口是 Spring Data 允许的，买家侧与卖家侧的查询口径本就不同，分开后互不干扰；
- 库存回补**不写** `product` 表，一律调用既有接口 `ProductRepository.restoreStock`，即分工文档要求的「库存回补走既有商品更新接口」。

这样代码结构与「每张共享表只有一个所有者、各人领域隔离」的分工完全对得上。

### 3.2 状态推进：显式推进表 + 条件更新

**（1）用显式表表达「顺序」，不用 `ordinal() + 1`**

```java
private static final Map<OrderStatus, OrderStatus> NEXT_STATUS = new EnumMap<>(OrderStatus.class);
static {
    NEXT_STATUS.put(OrderStatus.placed,    OrderStatus.confirmed);
    NEXT_STATUS.put(OrderStatus.confirmed, OrderStatus.packed);
    NEXT_STATUS.put(OrderStatus.packed,    OrderStatus.shipped);
    NEXT_STATUS.put(OrderStatus.shipped,   OrderStatus.completed);
}
```

`completed` / `cancelled` 不在表里，天然不可推进。若用 `ordinal() + 1`，将来枚举顺序调整或插入新状态会悄悄改变业务规则，而这张表改没改、怎么改都一目了然。之所以没有把这个方法加进 `OrderStatus` 枚举，是因为那个文件属于开发 A，本次不动它。

**（2）写入用条件更新，把「校验 + 写入」压成一条语句**

```sql
update orders set status = :next where id = :id and status = :expected
```

「先 `select` 读出状态、判断合法、再 `update`」在并发下会让两个请求都读到 `placed` 并各自写入 `confirmed`，随后再各点一次就跳过了「商家确认」——等于隐式地「跳过状态」。条件更新把判断放进 `where`，受影响行数为 0 即说明状态已被他人改动，直接返回 409「订单状态已被其他操作变更，请刷新后重试」。这与开发 A 下单时用 `where stock >= ?` 保证不超卖是同一套思路。

**（3）「不可跳过」不只靠前端**

接口支持 `{"to":"confirmed"}` 显式指定目标状态。前端当然只会显示「推进到下一状态」一个按钮，但如果有人直接调接口传 `packed`，服务端必须拒绝——把规则放在能被绕过的位置等于没有规则。

### 3.3 取消的库存回补：顺序决定正确性

取消有两件事：改状态、回补库存。顺序必须是**先条件更新状态，成功后才回补**：

```java
if (orders.changeStatus(orderId, current, OrderStatus.cancelled) == 0) {
    throw conflict("订单状态已被其他操作变更，请刷新后重试");
}
// 只有走到这里的那一次请求才回补库存
for (OrderItemEntity line : orderItems.findByOrderIdOrderByIdAsc(orderId)) {
    products.restoreStock(line.getProductId(), line.getQuantity());
}
```

若反过来「先回补、后改状态」，并发的两次取消会各回补一次，库存凭空变多。把状态更新当作一次性的「抢锁成功」，库存回补就天然是幂等的。

回补之后重新读商品记录再拼进响应，是为了把回补后的真实库存和状态（例如 `depleted → active`）如实回报，而不是让前端猜。

### 3.4 两个查询入口合并为一个资源

14209（按商品）与 14214（按客户）的返回结构完全一致，差别的只是过滤维度，因此实现为一个接口的两个过滤参数：

| 用法 | 路径 |
| --- | --- |
| 14209 商品订单记录 | `GET /api/admin/orders?productId=1` |
| 14214 客户购买历史 | `GET /api/admin/orders?customerId=3` |
| 全部订单总览 | `GET /api/admin/orders` |

好处有三点：前端只需要一套渲染逻辑；不会出现「同一笔订单在两个接口里字段不一样」；后续再加维度（例如按状态筛选）只是多一个参数。

两个参数同时存在时返回 400，而不是取交集——14209 问的是「这件商品被人下过哪些单」，14214 问的是「这个客户下过哪些单」，同时传既没有业务含义，也容易把误用变成一个看似正常的结果。

**商品维度用子查询而不是 join**：一条订单可能含同一商品的多条明细，join 会让订单重复出现，还要靠 `distinct` 去重，分页的总数也会跟着算错：

```sql
select o from OrderEntity o
 where o.id in (select i.orderId from OrderItemEntity i where i.productId = :productId)
```

### 3.5 排序口径与买家端对齐

卖家看到的订单顺序必须和买家「我的订单」一致，否则同一笔订单在两边位置不同会让人怀疑数据出错。两边都用 `createdAt desc, id desc`（同一时间戳再用 id 兜底，保证排序稳定）。

### 3.6 前端不做状态机

页面上的按钮不是「按状态 if/else 拼出来的」，而是直接读后端给的字段：

- `advanceable` 决定要不要显示「推进到「××」」按钮，`nextStatusLabel` 决定按钮文字；
- `cancellable` 决定要不要显示「取消订单」按钮。

原因很实际：状态机一旦前后端各写一份，两边就一定会慢慢走偏（比如后端加了新状态、前端忘了处理，按钮就消失或点不动）。只让服务端算、前端只负责显示，规则永远只有一处。

### 3.7 错误响应与既有模块同构

复用订单域已有的 `OrderException` + `OrderErrors`，卖家侧的错误体与买家侧完全一样（`error` / `fieldErrors` / `itemErrors`），前端可以用同一套错误处理；未登录统一 401，页面跳卖家登录页。

---

## 四、主要改动涉及的模块与文件

### 4.1 新增文件

| 文件 | 说明 |
| --- | --- |
| `src/main/java/com/coursewise/shopping/seller/SellerOrderRepository.java` | 卖家侧订单仓储：按客户 / 按商品分页查询，以及一条状态条件更新（推进与取消共用） |
| `src/main/java/com/coursewise/shopping/seller/SellerOrderItemRepository.java` | 商品维度的两个聚合查询：涉及订单笔数、累计下单件数 |
| `src/main/java/com/coursewise/shopping/seller/SellerOrderService.java` | 本领域核心业务：推进状态、卖家取消（含库存回补）、两个查询入口、维度汇总 |
| `src/main/java/com/coursewise/shopping/seller/SellerOrderController.java` | 卖家订单 REST 接口（4 条路由） |
| `public/seller-orders.html` | 卖家订单处理页：按商品 / 按客户 / 全部订单三个页签 |
| `public/seller-orders.js` | 卖家订单处理页脚本（自带请求封装，不依赖 `shop.js` / `admin.js`） |
| `src/test/java/com/coursewise/shopping/SellerOrderIntegrationTest.java` | 本次需求的集成测试（14 条用例） |

### 4.2 修改文件（上一次迭代已存在，本次只做追加）

| 文件 | 上一次迭代的状态 | 本次改动内容 |
| --- | --- | --- |
| `public/admin.html` | 卖家工作台，头部只有「查看小店」「退出登录」 | 头部**追加**「订单处理」入口，指向 `/seller-orders.html`；未改动任何已有页签与表单 |
| `public/catalog.css` | 已含商品列表、详情、购物车、收藏、我的订单等样式 | **追加**卖家订单处理页需要的样式（维度选择器、汇总条、卖家订单行栅格、明细高亮行），全部写在文件末尾的独立分区 |

被**引用但未修改**的既有文件：`OrderEntity`、`OrderItemEntity`、`OrderStatus`、`OrderItemRepository`、`ProductRepository`（`restoreStock`）、`CustomerRepository`、`ShoppingService.requireSession`、`OrderException`、`OrderErrors`。

### 4.3 接口一览（本次新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/orders?productId=&customerId=&page=&size=` | 卖家订单列表；`productId` 即 14209，`customerId` 即 14214，都不传为全部订单 |
| GET | `/api/admin/orders/{id}` | 单笔订单详情（卖家视角，含客户信息与可用动作） |
| POST | `/api/admin/orders/{id}/advance` | 14207 推进状态；请求体可选 `{"to":"confirmed"}` |
| POST | `/api/admin/orders/{id}/cancel` | 14208 卖家取消订单，按明细回补库存 |

---

## 五、开发与验证过程

1. **先读代码再动手**：把开发 A 已完成的订单域（实体、枚举、仓储、服务、控制器、前端 `orders.html` 与 `shop.js`）通读一遍，确认三件事——订单只有下单时间一个时间戳、状态整单流转、库存扣减走条件更新；据此确定我这一侧只需写 `order.status`，库存回补复用 `restoreStock`。
2. **后端自底向上**：先写两个仓储（只读查询 + 一条条件更新），再写 `SellerOrderService`，最后暴露 `SellerOrderController`。每一步用 `mvn -o compile` 确认编译通过。
3. **测试驱动收敛细节**：写 14 条集成测试覆盖四条需求的验收标准，过程中修正了两处：
   - 取消与推进的方法一开始漏了 `@Transactional`，`@Modifying` 的条件更新在没有事务时会抛 `TransactionRequiredException`，补上后正常；
   - 测试里一处库存期望值算错（只算了回补、漏掉了仍被占用的那笔订单），据实际数据修正为 `20 - 1 - 2 + 2 = 19`。
4. **端到端实测**：把应用真正跑起来（独立 SQLite 库 + 独立上传目录，不污染仓库里的 `data/shop.db`），用脚本造出 4 件商品、3 位客户、6 笔覆盖五种状态的订单，再逐条打接口验证合法路径与非法路径，完整记录见 `验证/接口自检.txt`。重点验证了：
   - 合法推进逐格前进，`nextStatusLabel` 同步变化；
   - 跳过（`confirmed → shipped`）、回退（`confirmed → placed`）、原地推进均被 409 拒绝且状态不变；
   - 「交易完成」既不能推进也不能取消；
   - 「开始发货」的订单卖家仍可取消，库存回补 1 件；
   - 同一笔订单重复取消被拒绝，库存不会被二次回补；
   - 未登录访问四个接口均 401；不存在的商品 / 客户 404；两个维度同时指定 400。
5. **前端实现与走查**：新建卖家订单处理页，三个页签切换维度，商品维度下拉带库存与状态提示；订单行内展开明细，商品维度的命中行高亮。逐项检查了：页签切换后选择器与列表同步刷新、动作按钮只按后端字段渲染、动作完成后列表刷新且提示不被冲掉、分页条在只有一页时按钮禁用。
6. **测试执行**：`mvn -o test -Dtest=SellerOrderIntegrationTest` 结果 `Tests run: 14, Failures: 0, Errors: 0`。同时回归执行了开发 A 的 `OrderIntegrationTest`（`Tests run: 20, Failures: 0, Errors: 0`），确认本次新增的仓储与接口没有影响买家侧。

### 5.1 测试用例与需求的对应关系

本测试类**只覆盖本人负责的 4 条需求**（14207 / 14208 / 14209 / 14214），共 14 条用例。因为要验证条件更新、事务与库存回补，测试基于 `@SpringBootTest` 启动完整上下文，会连带加载其他成员的组件，这属于依赖加载，不代表测试范围扩展到他们的功能。

| 需求 | 覆盖用例 |
| --- | --- |
| 14207 推进订单状态 | 五状态逐格推进（`advanceFollowsTheFiveStatusesOneStepAtATime`）、不可跳过 / 原地（`advanceRefusesToSkipOrStayInPlace`）、不可回退 / 交易完成不可撤销（`advanceCannotGoBackOrUndoCompletion`）、未登录拦截（`advanceRequiresSellerLogin`） |
| 14208 卖家取消订单 | 取消回补库存且订单留痕（`sellerCancelRestoresStockAndKeepsOrderCancelled`）、已发货仍可取消且不可重复取消（`sellerCanCancelEvenAfterShippingButNotAfterCompletion`）、售罄下架商品走既有接口恢复在售（`sellerCancelReListsDepletedProductThroughExistingStockInterface`）、未登录 / 订单不存在（`sellerCancelRequiresLoginAndKnownOrder`） |
| 14209 查看商品订单记录 | 只返回涉及该商品的订单并给出汇总与高亮（`productOrderRecordsListEveryOrderInvolvingThatProduct`）、覆盖全部状态且可分页（`productOrderRecordsIncludeEveryStatusAndCanBePaged`）、未登录 / 不存在商品 / 两维互斥（`productOrderRecordsRequireLoginAndValidateInput`） |
| 14214 查看客户购买历史 | 只看该客户的订单且倒序（`customerPurchaseHistoryListsAllOrdersOfOneCustomer`）、在客户维度直接推进与取消（`customerPurchaseHistorySupportsAdvanceAndCancel`）、未登录 / 不存在客户（`customerPurchaseHistoryRequiresLoginAndKnownCustomer`） |

---

## 六、功能完成情况

| 需求 | 完成情况 | 建设性质 | 说明 |
| --- | --- | --- | --- |
| 14207 推进订单状态 | 已完成 | 全新功能 | 五状态逐格推进、跳过 / 回退 / 原地 / 终态均 409 拒绝、条件更新防重复推进，测试与实测均验证通过 |
| 14208 卖家取消订单 | 已完成 | 全新功能 | 交易完成前（含已发货）可取消、库存按明细回补并复用既有商品更新接口、重复取消不二次回补，验证通过 |
| 14209 查看商品订单记录 | 已完成 | 全新功能 | 按商品维度覆盖全部状态、汇总笔数与件数、明细高亮该商品、分页，验证通过 |
| 14214 查看客户购买历史 | 已完成 | 全新功能 | 按客户维度列出全部订单、可就地推进与取消、客户之间隔离，验证通过 |

- **新增页面 1 个**：`seller-orders.html`（含脚本 `seller-orders.js`）；**改造页面 1 个**：`admin.html`（头部加入口）；**追加样式 1 个**：`catalog.css`。
- 自动化测试：`SellerOrderIntegrationTest` 14 条用例全部通过（`Failures: 0, Errors: 0`）；回归执行 `OrderIntegrationTest` 20 条同样全部通过。
- 接口实测：四条需求的合法与非法路径逐条打真实服务，记录见 `验证/接口自检.txt`。
- **未包含内容**：订单的创建、下单扣库存、客户取消、「我的订单」（14201～14205）属开发 A；商品状态机与库存写入口（14206 / 14213 / 14215）属开发 D。本次未修改这些功能的业务代码。

---

## 七、附件

### 7.1 `截图/`

本次需求的功能截图，按业务流程编号（截图取自本地真实运行的应用：造出 4 件商品、3 位客户、6 笔订单的演示数据后逐张拍摄）：

| 截图 | 内容 |
| --- | --- |
| `01_卖家后台_商品管理.png` | 卖家工作台的「商品管理」页：4 件演示商品及其在下单后的库存（钢笔 9 / 手账本 2 / 墨水 2 / 蓝牙耳机 7） |
| `02_按商品维度_涉及订单与汇总.png` | 14209 按商品查看：顶部汇总（涉及订单 2 笔 / 累计下单 3 件 / 当前库存 9 / 在售）与涉及该商品的全部订单 |
| `03_按商品维度_本商品明细高亮.png` | 展开一笔订单，属于该商品的明细行高亮并标「本商品」，同单其他商品不高亮 |
| `04_按客户维度_客户购买历史.png` | 14214 按客户查看：客户信息条与该客户的**全部**订单（含「已取消」那一笔） |
| `05_推进订单状态_按钮为下一状态.png` | 14207 「客户下单」的订单只提供「推进到「商家确认」」一个按钮，没有任何跳过 / 回退入口 |
| `06_推进订单状态_成功提示.png` | 14207 推进成功后：提示「订单 2 已推进：商家确认」、状态标签变化、按钮同步变为「推进到「备货完成」」 |
| `07_推进订单状态_跳过被拒绝.png` | 14207 手工构造跳过请求（`{"to":"shipped"}`）被 **409** 拒绝，左侧列表状态未变 |
| `08_交易完成_不可推进不可取消.png` | 14207 「交易完成」订单显示「已到终态」，该行没有任何按钮 |
| `09_卖家取消_库存回补提示.png` | 14208 取消「客户下单」的订单：提示「订单 6 已取消，共回补 1 件商品库存」 |
| `10_卖家取消_已发货仍可取消.png` | 14208 **「开始发货」**的订单（00000005）仍带「取消订单」按钮——卖家发货后仍可取消 |
| `10b_卖家取消_已发货取消后提示.png` | 14208 同上那笔订单取消成功后的状态与「共回补 1 件商品库存」提示（接续 `10`） |

### 7.2 `验证/`

- `接口自检.txt`：四条需求在真实运行的服务上逐条调用接口的完整记录（含 HTTP 状态码与响应体），覆盖合法路径与非法路径。它是 `src/test` 里 14 条自动化用例之外的补充证据——自动化用例断言「结果对不对」，这份记录则直接留下服务端对越界请求的原始回应（例如跳过状态时 409 的完整文案），答辩被问到「怎么证明不可跳过」时可以直接翻。

### 7.3 `汇报PPT/`

- `挑战需求B迭代汇报-陈心冉.pptx`：三页汇报（负责部分 / 实现过程 / 成果），由同目录 `build_ppt.py` 生成，已把 `02`（14209 商品维度）、`06`（14207 推进成功）、`09`（14208 取消回补）三张截图嵌入第 3 页。
- `build_ppt.py`：生成脚本。它按文件名从 `../截图/` 取图、等比缩放放进版式（不拉伸变形）；改过截图后重新执行 `python build_ppt.py` 即可刷新，缺图时自动退回纯文字版式。
