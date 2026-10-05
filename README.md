# 城市图书馆跨馆借还调拨 · 逾期罚款与预约排队服务

一个城市级公共图书馆后端：读者可以检索本馆与各分馆库存、预约排队、跨馆调拨借书；馆员与物流员处理出库、到馆、还书核验、罚款赔偿与各类异常；管理端查看调拨效率、热门预约、逾期与丢损。**热门书预约人数过多时，服务根据等待人数、复本数量、续借限制与跨馆调拨成本建议新增采购或暂停续借；新增复本到馆后按预约时间与各分馆读者等待时长均衡分配（避免全部流向总馆），通知写明预计取书窗口；馆员可查看分配建议并手动调整，调整原因进入馆藏流转记录。**

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · PostgreSQL 16 · Flyway · springdoc OpenAPI 3 / Swagger UI · Maven · Docker Compose。

---

## 1. 一键启动（Docker Compose，含 PostgreSQL）

```bash
docker compose up --build
```

启动后：

- 服务地址：http://localhost:8080
- Swagger UI（可直接试调全部接口）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

首次启动 Flyway 自动建表并写入种子数据（5 个分馆、4 部书目、13 册馆藏、3 位读者、全部规则参数）。停止并清理：`docker compose down -v`。

## 2. 本地开发启动

前置：JDK 17、Maven 3.8+、PostgreSQL 14+。

```bash
# 1) 准备数据库
createdb library
# 2) 设置数据库连接（可选，默认值如下）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/library
export SPRING_DATASOURCE_USER=library
export SPRING_DATASOURCE_PASSWORD=library
# 3) 运行（Flyway 自动迁移）
mvn spring-boot:run
# 4) 打包
mvn package
java -jar target/city-library.jar
```

测试使用 H2（PostgreSQL 兼容模式）+ 同一套 Flyway 迁移脚本，无需本地数据库：

```bash
mvn test
```

## 3. 测试账号（种子数据）

| 角色 | 账号 | 说明 |
| --- | --- | --- |
| 读者 | `R001` 张三 | 借书证号即接口中的 `readerCardNo` |
| 读者 | `R002` 李四 | 同上 |
| 读者 | `R003` 王五 | 同上 |
| 馆员 / 物流员 | 请求体中的 `staff` / `staffName` 字段 | 演示环境不做登录鉴权，操作留痕记录操作人姓名 |

分馆代码：`CENTRAL` 中心馆、`NORTH` 城北、`SOUTH` 城南、`WEST` 城西、`EAST` 城东远端分馆。
书目 ID：1《三体》（有电子书）、2《红楼梦》、3《解忧杂货店》（有电子书）、4《平凡的世界》。

## 4. 快速验证（端到端 curl 剧本）

以下剧本覆盖题目主链路：**检索 → 预约锁条码 → 调拨出库 → 到馆通知 → 借出 → 归还 → 统计**。

```bash
# 读者在城西检索《三体》：本馆 0 册、城东可调拨 1 册、ETA 72h、有电子书替代
curl -s -G "http://localhost:8080/api/catalog/search" \
  --data-urlencode "keyword=三体" --data-urlencode "pickupLibraryCode=WEST" | jq

# R001 预约，取书点城西 → 系统锁定城东条码并生成调拨任务
curl -s -X POST http://localhost:8080/api/holds/bibs/1 -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R001","pickupLibraryCode":"WEST"}' | jq

# 物流员看待出库任务、出库（记录出库馆/目的馆/批次/包裹号/ETA）
TASK_ID=$(curl -s http://localhost:8080/api/transfers/tasks/pending | jq '.[0].id')
curl -s -X POST http://localhost:8080/api/transfers/pickups -H 'Content-Type: application/json' \
  -d "{\"taskIds\":[$TASK_ID],\"staffName\":\"物流员小赵\"}" | jq
PKG=$(curl -s "http://localhost:8080/api/transfers/tasks/$TASK_ID" | jq -r .packageNo)

# 到馆签收 → R001 收到 READY 取书通知，图书在城西预约架（取书时限 3 天）
curl -s -X POST http://localhost:8080/api/transfers/arrivals -H 'Content-Type: application/json' \
  -d "{\"packageNo\":\"$PKG\"}" | jq
curl -s http://localhost:8080/api/notifications/readers/R001 | jq

# R001 借出并查看应还日期/续借资格
BARCODE=$(curl -s "http://localhost:8080/api/holds/readers/R001" | jq -r '.[0].itemBarcode')
LOAN=$(curl -s -X POST http://localhost:8080/api/loans/checkouts -H 'Content-Type: application/json' \
  -d "{\"readerCardNo\":\"R001\",\"itemBarcode\":\"$BARCODE\",\"libraryCode\":\"WEST\"}")
LOAN_ID=$(echo "$LOAN" | jq .id)
curl -s "http://localhost:8080/api/loans/$LOAN_ID/renew-eligibility" | jq

# 还书核验（可传 damaged/missingPages/accessoryMissing，这里为完好归还）
curl -s -X POST http://localhost:8080/api/loans/returns -H 'Content-Type: application/json' \
  -d "{\"itemBarcode\":\"$BARCODE\",\"libraryCode\":\"WEST\"}" | jq

# 管理端：总览/分馆/热门/读者/批次/规则参数
curl -s "http://localhost:8080/api/admin/dashboard?days=30" | jq
curl -s "http://localhost:8080/api/admin/libraries" | jq
curl -s "http://localhost:8080/api/admin/bibs/popular" | jq
curl -s "http://localhost:8080/api/admin/readers?cardNo=R001" | jq
curl -s "http://localhost:8080/api/admin/batches" | jq
curl -s "http://localhost:8080/api/admin/policies" | jq
```

热门书采购与复本调配剧本（本题新增能力，以书目 4《平凡的世界》为例）：

```bash
# 前置：唯一一册 P-0001 被借出，三位读者分别在城南/城西/城东排队，无复本可分
curl -s -X POST http://localhost:8080/api/loans/checkouts -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R001","itemBarcode":"P-0001","libraryCode":"NORTH"}' | jq
for p in "R002 SOUTH" "R003 WEST"; do set -- $p
  curl -s -X POST http://localhost:8080/api/holds/bibs/4 -H 'Content-Type: application/json' \
    -d "{\"readerCardNo\":\"$1\",\"pickupLibraryCode\":\"$2\"}" | jq; done

# 1) 采购/续借建议：综合等待人数、复本、续借受阻、跨馆调拨成本；给出各分馆建议新增册数
curl -s http://localhost:8080/api/admin/acquisitions/demand/bibs/4 | jq
curl -s http://localhost:8080/api/admin/acquisitions/demand/hot | jq

# 2) 新增复本统一到馆“总馆 CENTRAL”：系统按预约时间+各分馆等待时长均衡分配，
#    久等的分馆读者优先跨馆调拨，复本不会全部留在总馆；返回每册 mode 与预计取书窗口
curl -s -X POST http://localhost:8080/api/admin/acquisitions/copies \
  -H 'Content-Type: application/json' \
  -d '{"bibId":4,"libraryCode":"CENTRAL","count":2,"staff":"采购馆员小陈","note":"热门书紧急加采"}' | jq

# 3) 读者收到的通知含“预计取书窗口”（调拨为 预计到馆 ~ +hold.pickupDays 天）
curl -s http://localhost:8080/api/notifications/readers/R002 | jq '.[0]'

# 4) 馆员查看建议后人工改配（把某册改配给指定排队预约）；reason 必填，进入馆藏流转记录
curl -s -X POST http://localhost:8080/api/admin/acquisitions/manual-adjust \
  -H 'Content-Type: application/json' \
  -d '{"barcode":"ACQ-4-XXXXXXXX","targetHoldId":4,"staff":"馆员老李","reason":"城东读者出差无法取书，优先照顾到馆现场读者"}' | jq

# 5) 馆藏流转记录（ACQUIRE 到馆 / ALLOCATE 自动分配 / MANUAL_ADJUST 人工改配，全部带操作人与原因）
curl -s "http://localhost:8080/api/admin/acquisitions/events/bibs/4" | jq
curl -s "http://localhost:8080/api/admin/acquisitions/events/items/ACQ-4-XXXXXXXX" | jq
```

异常链路接口（均在 Swagger 中可试）：

- 取书超时释放：`POST /api/holds/jobs/expire`（定时任务每小时自动执行，也可手动触发）
- 物流丢包：`POST /api/transfers/tasks/{id}/lost` → 馆藏置 LOST、生成 `LOGISTICS` 赔偿单、读者免责保位并自动重新调拨
- 读者取消：`POST /api/holds/{id}/cancel`（未出库立即解锁；在途则到馆释放给下一位）
- 声称已还：`POST /api/claims/loans/{loanId}` → 馆员 `.../found`（入库并豁免逾期）、`.../not-found`（赔书价）、`.../waive`（馆方豁免，必填理由）
- 污损/缺页/附件：还书接口置位 → 分别生成赔偿单，污损书退出预约流转
- 临时闭馆：`POST /api/libraries/{code}/closure` → 在该馆待取预约与临期借阅自动顺延 `closure.graceHours`
- 罚款人工调整：`POST /api/fines/{id}/adjustments`（REDUCE/WAIVE，必须填理由；原值/新值/操作人/规则依据全部审计留痕，`GET /api/fines/{id}/adjustments` 可查）

## 5. 领域模型与状态机

- `items` 馆藏条码：`AVAILABLE → LOCKED → IN_TRANSIT → ON_HOLD → CHECKED_OUT`，异常分支 `LOST / DAMAGED`。
- `holds` 预约：`QUEUED → READY → BORROWED`；异常 `CANCELLED / EXPIRED / LOST`。
- `transfer_tasks` 调拨：`CREATED → PICKED_UP → ARRIVED`；异常 `LOST / CANCELLED`；按 `transfer_batches` 物流批次聚合包裹。
- `loans` 借阅：`ACTIVE → RETURNED`；争议 `CLAIMED_RETURNED`；确认丢失 `LOST`。
- `fines` 罚款/赔偿：类型含逾期、污损、缺页、附件、赔书、物流丢包、人工；承担方 `READER / LIBRARY / LOGISTICS`；状态 `OPEN / PAID / WAIVED / REDUCED`，调整记录在 `fine_adjustments`。
- `collection_events` 馆藏流转记录：`ACQUIRE` 新增复本到馆 / `ALLOCATE` 系统平衡自动分配 / `MANUAL_ADJUST` 馆员人工改配；记录复本、来源/目的馆、预约、操作人（系统记 `SYSTEM`）与原因，人工调整原因强制留痕。

一致性设计要点：

1. **单一再分配入口**：取消、超时、到馆失效、完好归还产生的在馆副本，统一经 `HoldService.reallocate(item, library)` 分配给队列首位（取书点在本馆的等待者优先，省一趟物流），无人排队则上架。
2. **统一加锁顺序**：所有同时操作“预约 ↔ 馆藏”的入口先锁该书目活跃预约行（`SELECT … FOR UPDATE`），再锁具体馆藏行，避免并发抢同一本与死锁。新增复本分配与人工改配同样走该顺序。
3. **新增复本平衡分配**：`HoldService.assignNewCopies` 对每册新复本取各取书分馆队首；全局最早者在本馆即上架，外馆队首比本馆队首多等超过 `acquire.fairnessGraceHours`（或本馆无人）时跨馆调拨给久等分馆，在宽限窗口内才优先本馆省物流——即使新复本统一配送到总馆也不会全部流向总馆。
4. **人工改配留痕**：馆员改配前校验复本未出库在途/未借出，撤销原未出库调拨任务并把原预约复位为未分配排队（保留按预约时间的 FIFO 位置），通知双方，`MANUAL_ADJUST` 原因强制写入 `collection_events`。
5. **异常状态联动**：丢包/损坏/争议/闭馆在库存、预约、借阅、罚款之间同步变更，且同一事务提交。

## 6. 公平性与成本规则（可在管理端调整，见 `policies`）

| 规则 key | 默认值 | 含义 |
| --- | --- | --- |
| `loan.periodDays` | 30 | 标准借阅期限 |
| `loan.renewLimit` | 1 | 续借次数上限；且**该书有人排队时禁止续借**——热门书不被少数读者长期占用 |
| `hold.pickupDays` | 3 | 到馆取书时限，超时释放给下一位 |
| `hold.maxPerReader` | 10 | 单人同时有效预约上限 |
| `fine.daily` / `fine.cap` | 0.5 元/天 / 20 元 | 逾期罚款单价与单册封顶 |
| `fee.damage/missingPages/accessory/replacement/logisticsLoss` | 10/20/5/50/60 | 污损、缺页、缺附件、赔书、物流丢包赔付 |
| `transfer.protectionRatio` | 0.5 | **外馆保护**：调走 1 册后该馆留存可借数不得低于 ⌈比例×在流转馆藏数⌉，远端分馆库存不会被抽空（单册分馆默认保留） |
| `transfer.sameRegionHours/otherRegionHours` | 24/72 | 同/跨片区 ETA；选源馆时按 ETA、再按距离取最优，控制物流成本 |
| `closure.graceHours` | 24 | 临时闭馆时取书时限与应还日期自动顺延 |
| `acquire.queueThreshold` | 3 | 热门书判定：未满足等待人数达到该值时给出新增采购/暂停续借建议 |
| `acquire.fairnessGraceHours` | 48 | 新增复本平衡分配宽限：外馆队首比本馆队首早等待超过该小时数时，复本优先跨馆调拨给久等分馆，避免全部流向总馆 |

每条罚款都携带 `ruleBasis`（规则依据文案），馆员处理异常与人工调整时可直接向读者解释；调减/豁免强制填写理由并保留原值、新值、操作人、时间戳。

## 7. 本次实际执行过的验证

- `mvn test`：**19 个测试全部通过**（H2 + 真实 Flyway 迁移）：
  - `LibraryFlowTests`（11 个端到端业务流程）：检索视图与外馆保护、跨馆调拨全链路、FIFO 排队与超时释放、保护比例收紧、物流丢包保位重拨与物流赔偿、出库前/在途取消、逾期罚款累计封顶/逾期阻借/排队阻续、污损缺页附件赔偿与退出流转、声称已还三种结案与人工调整审计、临时闭馆双顺延、管理端统计一致性。
  - `AcquisitionFlowTests`（5 个新增端到端流程）：热门判定与采购/暂停续借建议（等待人数、复本、续借受阻、外馆保护、按等待分馆摊分而非总馆）；新复本统一到馆总馆时按各分馆等待时长跨馆均衡分配且通知含预计取书窗口；本馆新复本立即 READY 与本馆取书窗口；无人排队新复本直接上架；馆员人工改配原因/操作人入馆藏流转记录、原预约复位保位与通知、原调拨任务取消、在途复本禁止改配、空原因/空馆员被拒。
  - `WebApiTests`（3 个 HTTP 冒烟）：REST JSON、参数校验 400、OpenAPI 3 文档与 Swagger UI 可访问，以及采购建议/新增到馆/流转记录新接口的 HTTP 链路。
- `mvn package`：构建出 `target/city-library.jar`（约 59 MB，可执行 fat jar）。
- 真实数据库冒烟：已在本机用 **PostgreSQL 15** 独立实例实际启动该 JAR，Flyway V1→V3 全部迁移成功（含 `collection_events` 表与 `acquire.*` 策略项），并通过 curl 走通“借走唯一册制造短缺 → 三个分馆读者排队（均无复本可分）→ 采购建议判定热门、建议采购 3 册并摊分到 SOUTH/WEST/EAST（非总馆）、建议暂停续借 → 3 册新复本统一到馆 CENTRAL 后全部跨馆平衡派给三分馆（取书窗口 = +72h 到馆 ~ +3 天，通知含窗口）→ 空原因改配被 400 拒绝 → 馆员把 EAST 复本改配给总馆排队读者（原因留痕、原 EAST 读者复位保位并收通知、原 CENTRAL→EAST 任务 CANCELLED）→ `collection_events` 依次可见 ACQUIRE/ALLOCATE/MANUAL_ADJUST → Swagger UI / OpenAPI JSON 均 200”全链路。
- 未执行项：本环境无 Docker 守护进程，`docker compose up` 未在本机实际运行；Dockerfile 采用标准 Maven 多阶段构建 + eclipse-temurin:17-jre，且 compose 使用的 PostgreSQL 建表迁移脚本（含新增 V3）已在上述真实 PG 15 实例上验证通过。
