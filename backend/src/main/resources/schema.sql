-- 射箭馆（弓箭俱乐部）演示数据：建表 + 初始化数据
-- 回合的箭支走 round_arrow 这张有序集合连接表，shot_index 从 0 连续递增。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lanes (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  lane_no       VARCHAR(16)  NOT NULL COMMENT '道号',
  distance      INT          NOT NULL COMMENT '距离（米）：10/18/30/50',
  target_type   VARCHAR(32)  NOT NULL COMMENT '箭靶类型',
  status        VARCHAR(16)  NOT NULL COMMENT 'OPEN 开放 / OCCUPIED 占用 / MAINTENANCE 维护 / LOCKED 安全锁定',
  hourly_price  DECIMAL(10,2) NOT NULL COMMENT '每小时单价',
  occupant_id   BIGINT       NULL COMMENT '占用会员',
  occupant_name VARCHAR(32)  NULL,
  opened_at     DATETIME     NULL COMMENT '开台时间',
  planned_hours INT          NULL COMMENT '预计时长（小时）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lane_no (lane_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS members (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  card_no       VARCHAR(24)  NOT NULL COMMENT '会员卡号（封存档案保留原卡号可查）',
  name          VARCHAR(32)  NOT NULL,
  phone         VARCHAR(20)  NOT NULL,
  level         VARCHAR(16)  NOT NULL COMMENT 'NORMAL 普通 / SILVER 银 / GOLD 金',
  balance       DECIMAL(10,2) NOT NULL COMMENT '余额',
  register_date DATE         NOT NULL COMMENT '注册日期',
  total_spend   DECIMAL(12,2) NOT NULL COMMENT '累计消费',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 在用 / MERGED 已合并封存（历史档案，不可继续使用）',
  merged_into_id BIGINT      NULL COMMENT '封存时合并去向的保留会员 id（不加外键，避免与合并流程相互锁定）',
  merged_at     DATETIME     NULL COMMENT '合并封存时间',
  merge_reason  VARCHAR(500) NULL COMMENT '合并原因（值班经理填写）',
  merged_by     VARCHAR(32)  NULL COMMENT '执行合并的值班经理',
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_no (card_no),
  UNIQUE KEY uk_phone (phone),
  KEY idx_members_status (status),
  KEY idx_members_merged_into (merged_into_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= 模块九：重复会员档案合并 =================
-- 器材租借影子记录：同一件器材在历史误操作中分别挂在两张会员卡名下的异常登记。
-- 正常业务（租借 / 归还）不写本表；合并预演用它与正式租约取交集，识别「双方租出同一件器材」的互斥。
CREATE TABLE IF NOT EXISTS merge_equipment_rent_shadow (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  equipment_id BIGINT       NOT NULL,
  equip_code   VARCHAR(24)  NOT NULL,
  type         VARCHAR(16)  NOT NULL,
  brand        VARCHAR(32)  NOT NULL,
  renter_id    BIGINT       NOT NULL,
  rented_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_merge_equip_shadow_renter (renter_id),
  KEY idx_merge_equip_shadow_equip (equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 合并预演：值班经理选定保留档案 + 来源档案后生成，确认必须以预演版本为依据。
-- 状态机 PREVIEWED 待确认 → CONSUMED 已用于成功合并（一次性）；
-- 任一端确认成功即原子转为 CONSUMED，另一端凭同一版本确认会被拒绝并看到去向。
CREATE TABLE IF NOT EXISTS member_merge_preview (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  preview_no       VARCHAR(24)  NOT NULL COMMENT '预演版本号',
  keep_member_id   BIGINT       NOT NULL COMMENT '保留档案会员',
  source_member_id BIGINT       NOT NULL COMMENT '来源档案会员（合并后封存）',
  operator         VARCHAR(32)  NOT NULL COMMENT '发起预演的值班经理',
  reason           VARCHAR(500) NOT NULL COMMENT '合并原因',
  status           VARCHAR(16)  NOT NULL COMMENT 'PREVIEWED 待确认 / CONSUMED 已用于合并 / SUPERSEDED 已被新预演取代',
  -- 预演时刻两侧业务数据指纹：确认时重算比对，余额 / 报名 / 回合 / 租借 / 认证任一变化即拒绝
  keep_fingerprint   VARCHAR(64) NOT NULL,
  source_fingerprint VARCHAR(64) NOT NULL,
  -- 预演结论快照（互斥项 / 去重项 / 合并后金额等级），确认时回显，不落空
  snapshot_json    TEXT         NOT NULL,
  created_at       DATETIME     NOT NULL,
  consumed_at      DATETIME     NULL,
  consumed_by      VARCHAR(32)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merge_preview_no (preview_no),
  KEY idx_merge_preview_source (source_member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 合并轨迹：成功合并一条，记录操作者、原因、两侧原卡号 / 原姓名 / 合并前余额与累计消费、
-- 迁移与去重明细，供会员列表 / 详情页与会员本人查询（只能查看，不能撤销）。
CREATE TABLE IF NOT EXISTS member_merge_log (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  merge_no         VARCHAR(24)  NOT NULL COMMENT '合并单号',
  preview_id       BIGINT       NOT NULL COMMENT '所依据的预演版本 id',
  keep_member_id   BIGINT       NOT NULL,
  source_member_id BIGINT       NOT NULL COMMENT '来源档案仍保留在 members 表（status=MERGED），不删除',
  keep_card_no     VARCHAR(24)  NOT NULL COMMENT '保留卡号',
  source_card_no   VARCHAR(24)  NOT NULL COMMENT '来源原卡号（留存可查）',
  keep_name        VARCHAR(32)  NOT NULL,
  source_name      VARCHAR(32)  NOT NULL COMMENT '来源原姓名（留存可查）',
  keep_balance_before   DECIMAL(10,2) NOT NULL,
  source_balance_before DECIMAL(10,2) NOT NULL,
  keep_spend_before     DECIMAL(12,2) NOT NULL,
  source_spend_before   DECIMAL(12,2) NOT NULL,
  balance_after    DECIMAL(10,2) NOT NULL,
  total_spend_after DECIMAL(12,2) NOT NULL,
  level_before     VARCHAR(16)  NOT NULL,
  level_after      VARCHAR(16)  NOT NULL,
  rounds_moved     INT          NOT NULL DEFAULT 0 COMMENT '迁移计分回合数（箭值 / 完成时间原样保留）',
  enrollments_moved INT         NOT NULL DEFAULT 0 COMMENT '迁移课程报名条数',
  enrollments_dropped INT       NOT NULL DEFAULT 0 COMMENT '同一课程重复报名去重条数（保留较早一条）',
  equipment_moved  INT          NOT NULL DEFAULT 0 COMMENT '迁移当前器材租借数',
  lanes_moved      INT          NOT NULL DEFAULT 0 COMMENT '迁移占用箭道数',
  certs_moved      INT          NOT NULL DEFAULT 0 COMMENT '迁移认证申请 / 有效认证数',
  tournament_moved INT          NOT NULL DEFAULT 0 COMMENT '迁移赛事队员 / 箭值数',
  detail_json      TEXT         NULL COMMENT '逐项迁移 / 去重明细',
  reason           VARCHAR(500) NOT NULL,
  operator         VARCHAR(32)  NOT NULL COMMENT '合并操作者（值班经理，留存可查）',
  merged_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merge_log_no (merge_no),
  KEY idx_merge_log_keep (keep_member_id),
  KEY idx_merge_log_source (source_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rounds (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  round_no      VARCHAR(24)  NOT NULL COMMENT '回合号',
  member_id     BIGINT       NOT NULL,
  lane_id       BIGINT       NOT NULL,
  bow_type      VARCHAR(16)  NOT NULL DEFAULT 'RECURVE' COMMENT '本回合弓种：RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓',
  start_time    DATETIME     NOT NULL COMMENT '开始时间',
  completed_at  DATETIME     NULL COMMENT '完成（提交）时间；个人最好成绩同分平手时按它取更早的回合',
  arrow_count   INT          NOT NULL COMMENT '本组箭支数：6 或 12',
  total_score   INT          NOT NULL COMMENT '总分',
  average_score DECIMAL(5,2) NOT NULL COMMENT '平均环',
  personal_best TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否个人最好成绩',
  status        VARCHAR(16)  NOT NULL COMMENT 'ONGOING 进行中 / PAUSED 暂停（安全联锁） / SUBMITTED 已提交',
  PRIMARY KEY (id),
  UNIQUE KEY uk_round_no (round_no),
  KEY idx_round_member (member_id),
  KEY idx_round_lane (lane_id),
  KEY idx_round_bow (bow_type),
  CONSTRAINT fk_round_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_round_lane   FOREIGN KEY (lane_id)   REFERENCES lanes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS arrow_score (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  ring       VARCHAR(4)  NOT NULL COMMENT '环数标记：X/10/9/.../1/M',
  ring_value INT         NOT NULL COMMENT '计分环值：X 与 10 均为 10，M 为 0',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Round 到 ArrowScore 的有序集合连接表：shot_index 由 Hibernate 的 @OrderColumn 维护，
-- 保证列表顺序 = 射箭顺序（第 0 支、第 1 支 ...）。
CREATE TABLE IF NOT EXISTS round_arrow (
  round_id   BIGINT NOT NULL,
  arrow_id   BIGINT NOT NULL,
  shot_index INT    NOT NULL,
  PRIMARY KEY (round_id, shot_index),
  UNIQUE KEY uk_round_arrow_arrow (arrow_id),
  CONSTRAINT fk_ra_round FOREIGN KEY (round_id) REFERENCES rounds (id),
  CONSTRAINT fk_ra_arrow FOREIGN KEY (arrow_id) REFERENCES arrow_score (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS courses (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  course_name VARCHAR(64) NOT NULL COMMENT '课程名',
  coach       VARCHAR(32) NOT NULL COMMENT '教练',
  level       VARCHAR(16) NOT NULL COMMENT 'BASIC 初级 / ADVANCED 进阶 / COMPETITION 竞技',
  class_time  DATETIME    NOT NULL COMMENT '上课时间',
  capacity    INT         NOT NULL COMMENT '人数上限',
  enrolled    INT         NOT NULL DEFAULT 0 COMMENT '已报名',
  venue       VARCHAR(32) NOT NULL COMMENT '教室/场地',
  PRIMARY KEY (id),
  KEY idx_course_time (class_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS course_enroll (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  course_id   BIGINT   NOT NULL,
  member_id   BIGINT   NOT NULL,
  enroll_time DATETIME NOT NULL COMMENT '报名时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_enroll (course_id, member_id),
  CONSTRAINT fk_enroll_course FOREIGN KEY (course_id) REFERENCES courses (id),
  CONSTRAINT fk_enroll_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS equipment (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  equip_code  VARCHAR(24)  NOT NULL COMMENT '器材编号',
  type        VARCHAR(16)  NOT NULL COMMENT 'RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓 / GEAR 护具 / ARROW 箭支',
  brand       VARCHAR(32)  NOT NULL COMMENT '品牌',
  rent_price  DECIMAL(10,2) NOT NULL COMMENT '租金（元/小时）',
  status      VARCHAR(16)  NOT NULL COMMENT 'INSTOCK 在库 / RENTED 租出 / REPAIR 维修 / LOCKED 安全锁定',
  renter_id   BIGINT       NULL,
  renter_name VARCHAR(32)  NULL,
  rented_at   DATETIME     NULL COMMENT '租出时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_equip_code (equip_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= 安全停射联锁台 =================
-- 停射事件主表：值班经理创建后立即联锁箭道/回合/器材；
-- 状态机 PENDING 待处置 → REVIEWING 分项复核 → CLEARED 待放行 → RELEASED 已放行；
-- 任一复核不通过退回 PENDING（继续锁定），重新发起复核时 review_round +1。
CREATE TABLE IF NOT EXISTS safety_event (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_no     VARCHAR(24)  NOT NULL COMMENT '事件编号',
  reason       VARCHAR(24)  NOT NULL COMMENT '原因类别：LANE_DEVICE 箭道设备异常 / PERSON_INTRUSION 人员闯入射线 / EQUIP_SUSPECT 疑似器材故障 / OTHER 其他',
  severity     VARCHAR(16)  NOT NULL COMMENT '严重级别：NOTICE 一般 / MAJOR 严重 / CRITICAL 紧急',
  description  VARCHAR(500) NOT NULL COMMENT '现场说明',
  status       VARCHAR(16)  NOT NULL COMMENT 'PENDING / REVIEWING / CLEARED / RELEASED',
  review_round INT          NOT NULL DEFAULT 1 COMMENT '复核轮次',
  created_by   VARCHAR(32)  NOT NULL COMMENT '创建人（值班经理）',
  created_at   DATETIME     NOT NULL,
  released_by  VARCHAR(32)  NULL COMMENT '放行执行人（值班经理）',
  released_at  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_event_no (event_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件-箭道快照：prev_status 记录锁定前业务状态，放行时按此恢复，绝不粗暴覆盖。
CREATE TABLE IF NOT EXISTS safety_event_lane (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL,
  lane_id     BIGINT      NOT NULL,
  prev_status VARCHAR(16) NOT NULL COMMENT '锁定前状态：OPEN / OCCUPIED / MAINTENANCE',
  restored    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '放行时是否已恢复',
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_lane (event_id, lane_id),
  CONSTRAINT fk_sl_event FOREIGN KEY (event_id) REFERENCES safety_event (id),
  CONSTRAINT fk_sl_lane  FOREIGN KEY (lane_id)  REFERENCES lanes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件-器材快照：同样保留锁定前状态（租出中的器材放行后仍归原会员，renter 字段锁定期间不动）。
CREATE TABLE IF NOT EXISTS safety_event_equipment (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  event_id     BIGINT      NOT NULL,
  equipment_id BIGINT      NOT NULL,
  prev_status  VARCHAR(16) NOT NULL COMMENT '锁定前状态：INSTOCK / RENTED / REPAIR',
  restored     TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_equip (event_id, equipment_id),
  CONSTRAINT fk_se_event FOREIGN KEY (event_id)     REFERENCES safety_event (id),
  CONSTRAINT fk_se_equip FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 事件-回合快照：受影响箭道上进行中的回合被暂停，放行后从原进度继续。
CREATE TABLE IF NOT EXISTS safety_event_round (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL,
  round_id    BIGINT      NOT NULL,
  prev_status VARCHAR(16) NOT NULL COMMENT '锁定前状态：ONGOING',
  restored    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_round (event_id, round_id),
  CONSTRAINT fk_srd_event FOREIGN KEY (event_id) REFERENCES safety_event (id),
  CONSTRAINT fk_srd_round FOREIGN KEY (round_id) REFERENCES rounds (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分项复核：每个事件每一轮有 RANGE（教练）与 EQUIPMENT（器材管理员）两项，
-- (event_id, review_round, item) 唯一，同一复核项只能形成一份最终结论。
CREATE TABLE IF NOT EXISTS safety_review (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     BIGINT       NOT NULL,
  review_round INT          NOT NULL,
  item         VARCHAR(16)  NOT NULL COMMENT 'RANGE 射线与人员安全 / EQUIPMENT 器材检查',
  conclusion   VARCHAR(8)   NULL COMMENT 'PASS 通过 / FAIL 不通过，NULL 为待复核',
  reviewer     VARCHAR(32)  NULL COMMENT '复核人',
  reviewed_at  DATETIME     NULL,
  note         VARCHAR(500) NULL COMMENT '复核意见；不通过时必填原因',
  PRIMARY KEY (id),
  UNIQUE KEY uk_safety_review (event_id, review_round, item),
  CONSTRAINT fk_srv_event FOREIGN KEY (event_id) REFERENCES safety_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 状态轨迹：创建、发起复核、每次复核结论、复核齐通过、退回待处置、复射放行，全程留痕。
CREATE TABLE IF NOT EXISTS safety_event_log (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  event_id   BIGINT       NOT NULL,
  action     VARCHAR(24)  NOT NULL COMMENT 'CREATE / START_REVIEW / REVIEW_PASS / REVIEW_FAIL / BACK_PENDING / CLEARED / RELEASE',
  operator   VARCHAR(32)  NOT NULL COMMENT '操作人',
  role       VARCHAR(16)  NOT NULL COMMENT 'MANAGER 值班经理 / COACH 教练 / KEEPER 器材管理员',
  detail     VARCHAR(500) NULL,
  created_at DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_safety_log_event (event_id),
  CONSTRAINT fk_slog_event FOREIGN KEY (event_id) REFERENCES safety_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- lanes：12 行
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (1, 'A01', 10, '纸质靶', 'OPEN', 60.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (2, 'A02', 10, '纸质靶', 'OCCUPIED', 60.00, 3, '赵启明', '2026-09-22 10:20:00', 2);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (3, 'A03', 18, '三联靶', 'OPEN', 80.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (4, 'A04', 18, '三联靶', 'OPEN', 80.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (5, 'A05', 18, '三联靶', 'MAINTENANCE', 80.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (6, 'A06', 30, '复合靶', 'OPEN', 100.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (7, 'A07', 30, '复合靶', 'OCCUPIED', 100.00, 7, '郑楠', '2026-09-22 13:05:00', 3);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (8, 'A08', 30, '复合靶', 'OCCUPIED', 100.00, 5, '周然', '2026-09-22 15:40:00', 1);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (9, 'A09', 50, '三联靶', 'OPEN', 140.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (10, 'A10', 50, '三联靶', 'OPEN', 140.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (11, 'A11', 50, '3D动物靶', 'MAINTENANCE', 120.00, NULL, NULL, NULL, NULL);
INSERT INTO lanes (`id`, `lane_no`, `distance`, `target_type`, `status`, `hourly_price`, `occupant_id`, `occupant_name`, `opened_at`, `planned_hours`) VALUES (12, 'A12', 30, '复合靶', 'OPEN', 100.00, NULL, NULL, NULL, NULL);

-- members：12 行
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (1, 'AR-2023-0001', '陈子昂', '13800010001', 'GOLD', 2680.00, '2023-03-18', 12480.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (2, 'AR-2023-0002', '林悦', '13800010002', 'SILVER', 960.00, '2023-06-02', 3420.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (3, 'AR-2023-0003', '赵启明', '13800010003', 'NORMAL', 320.00, '2023-09-11', 680.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (4, 'AR-2024-0004', '孙嘉', '13800010004', 'GOLD', 4120.00, '2024-01-05', 15600.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (5, 'AR-2024-0005', '周然', '13800010005', 'NORMAL', 150.00, '2024-02-20', 240.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (6, 'AR-2024-0006', '吴桐', '13800010006', 'SILVER', 1280.00, '2024-04-09', 4100.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (7, 'AR-2024-0007', '郑楠', '13800010007', 'NORMAL', 480.00, '2024-05-16', 720.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (8, 'AR-2024-0008', '何清', '13800010008', 'GOLD', 3260.00, '2024-06-21', 10200.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (9, 'AR-2024-0009', '冯磊', '13800010009', 'SILVER', 880.00, '2024-07-30', 3200.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (10, 'AR-2025-0010', '许静', '13800010010', 'NORMAL', 260.00, '2025-01-12', 520.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (11, 'AR-2025-0011', '高翔', '13800010011', 'SILVER', 1560.00, '2025-03-08', 5200.00);
INSERT INTO members (`id`, `card_no`, `name`, `phone`, `level`, `balance`, `register_date`, `total_spend`) VALUES (12, 'AR-2025-0012', '唐宁', '13800010012', 'NORMAL', 100.00, '2025-05-27', 180.00);

-- rounds：13 行（bow_type 记录本回合使用弓种，是弓种认证的证据字段之一）
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (1, 'R20260915001', 1, 1, 'RECURVE', '2026-09-15 19:10:00', '2026-09-15 19:10:00', 6, 58, 9.67, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (2, 'R20260916001', 2, 3, 'TRADITIONAL', '2026-09-16 18:40:00', '2026-09-16 18:40:00', 6, 43, 7.17, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (3, 'R20260916002', 4, 6, 'COMPOUND', '2026-09-16 20:05:00', '2026-09-16 20:05:00', 12, 112, 9.33, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (4, 'R20260917001', 6, 4, 'RECURVE', '2026-09-17 19:30:00', '2026-09-17 19:30:00', 6, 45, 7.50, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (5, 'R20260918001', 8, 9, 'RECURVE', '2026-09-18 20:15:00', '2026-09-18 20:15:00', 12, 109, 9.08, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (6, 'R20260919001', 1, 10, 'RECURVE', '2026-09-19 19:00:00', '2026-09-19 19:00:00', 6, 58, 9.67, 0, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (7, 'R20260920001', 9, 8, 'COMPOUND', '2026-09-20 18:20:00', '2026-09-20 18:20:00', 6, 37, 6.17, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (8, 'R20260920002', 11, 7, 'COMPOUND', '2026-09-20 20:30:00', '2026-09-20 20:30:00', 12, 104, 8.67, 1, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (9, 'R20260921001', 4, 12, 'COMPOUND', '2026-09-21 19:45:00', '2026-09-21 19:45:00', 6, 59, 9.83, 0, 'SUBMITTED');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (10, 'R20260922001', 3, 2, 'RECURVE', '2026-09-22 10:20:00', NULL, 6, 0, 0.00, 0, 'ONGOING');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (11, 'R20260922002', 7, 7, 'COMPOUND', '2026-09-22 13:05:00', NULL, 12, 0, 0.00, 0, 'ONGOING');
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (12, 'R20260922003', 5, 8, 'RECURVE', '2026-09-22 15:40:00', NULL, 6, 0, 0.00, 0, 'ONGOING');
-- 历史回合：会员 2 去年的传统弓 18 米训练，用于一笔已过期认证的证据
INSERT INTO rounds (`id`, `round_no`, `member_id`, `lane_id`, `bow_type`, `start_time`, `completed_at`, `arrow_count`, `total_score`, `average_score`, `personal_best`, `status`) VALUES (13, 'R20250910001', 2, 3, 'TRADITIONAL', '2025-09-10 19:00:00', '2025-09-10 19:00:00', 6, 40, 6.67, 0, 'SUBMITTED');

-- arrow_score：72 行
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (1, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (2, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (3, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (4, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (5, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (6, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (7, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (8, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (9, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (10, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (11, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (12, 'M', 0);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (13, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (14, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (15, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (16, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (17, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (18, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (19, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (20, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (21, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (22, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (23, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (24, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (25, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (26, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (27, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (28, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (29, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (30, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (31, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (32, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (33, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (34, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (35, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (36, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (37, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (38, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (39, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (40, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (41, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (42, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (43, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (44, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (45, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (46, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (47, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (48, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (49, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (50, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (51, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (52, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (53, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (54, 'M', 0);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (55, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (56, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (57, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (58, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (59, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (60, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (61, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (62, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (63, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (64, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (65, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (66, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (67, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (68, 'X', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (69, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (70, '9', 9);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (71, '10', 10);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (72, '10', 10);
-- round 13（历史传统弓训练）6 支：7/7/6/8/6/6 = 40
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (73, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (74, '7', 7);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (75, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (76, '8', 8);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (77, '6', 6);
INSERT INTO arrow_score (`id`, `ring`, `ring_value`) VALUES (78, '6', 6);

-- round_arrow：72 行
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 1, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 2, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 3, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 4, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 5, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (1, 6, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 7, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 8, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 9, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 10, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 11, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (2, 12, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 13, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 14, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 15, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 16, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 17, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 18, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 19, 6);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 20, 7);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 21, 8);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 22, 9);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 23, 10);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (3, 24, 11);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 25, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 26, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 27, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 28, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 29, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (4, 30, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 31, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 32, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 33, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 34, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 35, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 36, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 37, 6);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 38, 7);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 39, 8);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 40, 9);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 41, 10);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (5, 42, 11);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 43, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 44, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 45, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 46, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 47, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (6, 48, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 49, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 50, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 51, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 52, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 53, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (7, 54, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 55, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 56, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 57, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 58, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 59, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 60, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 61, 6);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 62, 7);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 63, 8);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 64, 9);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 65, 10);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (8, 66, 11);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 67, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 68, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 69, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 70, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 71, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (9, 72, 5);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 73, 0);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 74, 1);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 75, 2);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 76, 3);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 77, 4);
INSERT INTO round_arrow (`round_id`, `arrow_id`, `shot_index`) VALUES (13, 78, 5);

-- courses：10 行
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (1, '反曲弓入门体验', '张岩', 'BASIC', '2026-09-23 19:00:00', 8, 5, '一号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (2, '站姿与撒放基础', '张岩', 'BASIC', '2026-09-24 19:30:00', 8, 4, '一号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (3, '瞄点与稳定训练', '李慕白', 'ADVANCED', '2026-09-25 20:00:00', 6, 3, '二号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (4, '复合弓调校实操', '王铮', 'ADVANCED', '2026-09-26 19:00:00', 6, 3, '器材工坊');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (5, '30米定距强化', '李慕白', 'ADVANCED', '2026-09-27 20:00:00', 6, 6, '二号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (6, '竞技反曲特训班', '陈亦驰', 'COMPETITION', '2026-09-28 19:00:00', 4, 3, '竞技馆');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (7, '室内18米积分赛', '陈亦驰', 'COMPETITION', '2026-09-29 19:30:00', 4, 2, '竞技馆');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (8, '传统弓礼仪与技法', '苏禾', 'BASIC', '2026-09-30 19:00:00', 10, 3, '三号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (9, '青少年安全射箭课', '苏禾', 'BASIC', '2026-10-01 10:00:00', 10, 5, '一号教学区');
INSERT INTO courses (`id`, `course_name`, `coach`, `level`, `class_time`, `capacity`, `enrolled`, `venue`) VALUES (10, '赛前心理与节奏', '陈亦驰', 'COMPETITION', '2026-10-02 19:00:00', 4, 2, '竞技馆');

-- course_enroll：36 行
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (1, 1, 1, '2026-09-13 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (2, 1, 2, '2026-09-13 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (3, 1, 3, '2026-09-13 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (4, 1, 5, '2026-09-13 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (5, 1, 7, '2026-09-13 09:38:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (6, 2, 3, '2026-09-14 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (7, 2, 5, '2026-09-14 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (8, 2, 10, '2026-09-14 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (9, 2, 12, '2026-09-14 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (10, 3, 2, '2026-09-15 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (11, 3, 6, '2026-09-15 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (12, 3, 11, '2026-09-15 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (13, 4, 4, '2026-09-16 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (14, 4, 6, '2026-09-16 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (15, 4, 9, '2026-09-16 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (16, 5, 1, '2026-09-17 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (17, 5, 2, '2026-09-17 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (18, 5, 4, '2026-09-17 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (19, 5, 6, '2026-09-17 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (20, 5, 9, '2026-09-17 09:38:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (21, 5, 11, '2026-09-17 09:45:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (22, 6, 1, '2026-09-18 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (23, 6, 4, '2026-09-18 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (24, 6, 8, '2026-09-18 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (25, 7, 4, '2026-09-19 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (26, 7, 8, '2026-09-19 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (27, 8, 7, '2026-09-20 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (28, 8, 10, '2026-09-20 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (29, 8, 12, '2026-09-20 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (30, 9, 3, '2026-09-12 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (31, 9, 5, '2026-09-12 09:17:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (32, 9, 7, '2026-09-12 09:24:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (33, 9, 10, '2026-09-12 09:31:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (34, 9, 12, '2026-09-12 09:38:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (35, 10, 1, '2026-09-13 09:10:00');
INSERT INTO course_enroll (`id`, `course_id`, `member_id`, `enroll_time`) VALUES (36, 10, 8, '2026-09-13 09:17:00');

-- equipment：12 行
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (1, 'E-RC-01', 'RECURVE', 'Hoyt', 45.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (2, 'E-RC-02', 'RECURVE', 'W&W', 40.00, 'RENTED', 3, '赵启明', '2026-09-22 10:25:00');
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (3, 'E-RC-03', 'RECURVE', 'KAP', 38.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (4, 'E-CP-01', 'COMPOUND', 'Mathews', 80.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (5, 'E-CP-02', 'COMPOUND', 'Hoyt', 75.00, 'RENTED', 7, '郑楠', '2026-09-22 13:10:00');
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (6, 'E-CP-03', 'COMPOUND', 'PSE', 70.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (7, 'E-TR-01', 'TRADITIONAL', '三利达', 35.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (8, 'E-TR-02', 'TRADITIONAL', '黑石', 30.00, 'REPAIR', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (9, 'E-GR-01', 'GEAR', 'Fivics', 12.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (10, 'E-GR-02', 'GEAR', 'Cartel', 15.00, 'RENTED', 5, '周然', '2026-09-22 15:45:00');
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (11, 'E-AR-01', 'ARROW', 'Easton', 20.00, 'INSTOCK', NULL, NULL, NULL);
INSERT INTO equipment (`id`, `equip_code`, `type`, `brand`, `rent_price`, `status`, `renter_id`, `renter_name`, `rented_at`) VALUES (12, 'E-AR-02', 'ARROW', 'Gold Tip', 18.00, 'INSTOCK', NULL, NULL, NULL);

-- ================= 模块七：馆内团体淘汰赛计分台 =================
-- 赛事：值班经理建立，选择 4 或 8 支队（每队固定三名现有会员），设置每场局数。
-- 状态机 DRAFT 报名中（可换队/换队员）→ ONGOING 已开赛（队伍与对阵冻结）→ FINISHED 已完赛。
CREATE TABLE IF NOT EXISTS tournament (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  name            VARCHAR(64) NOT NULL COMMENT '赛事名称',
  team_size       INT         NOT NULL COMMENT '参赛队数：4 或 8',
  ends_per_match  INT         NOT NULL COMMENT '每场局数（每局三名队员各射规定箭数）',
  arrows_per_end  INT         NOT NULL COMMENT '每名队员每局规定箭数',
  status          VARCHAR(16) NOT NULL COMMENT 'DRAFT 报名中 / ONGOING 进行中 / FINISHED 已完赛',
  current_round   INT         NULL COMMENT '当前轮次：最早存在未确认场次的那一轮',
  champion_team_id BIGINT     NULL COMMENT '冠军队',
  created_by      VARCHAR(32) NOT NULL COMMENT '建立人（值班经理）',
  created_at      DATETIME    NOT NULL,
  started_at      DATETIME    NULL COMMENT '开赛时间（开赛即冻结队伍与对阵）',
  finished_at     DATETIME    NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 参赛队：seed_no 为开赛后固定的种子位（决定首轮对阵与晋级走向）
CREATE TABLE IF NOT EXISTS tournament_team (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  tournament_id BIGINT      NOT NULL,
  team_name     VARCHAR(48) NOT NULL,
  seed_no       INT         NULL COMMENT '种子位（开赛时按建队顺序固定，1..team_size）',
  final_rank    INT         NULL COMMENT '最终名次（冠军=1）',
  created_at    DATETIME    NOT NULL,
  PRIMARY KEY (id),
  KEY idx_team_tournament (tournament_id),
  CONSTRAINT fk_team_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 队-会员：一队固定三名会员；(tournament_id, member_id) 唯一，同一会员不能同时出现在两支队伍
CREATE TABLE IF NOT EXISTS tournament_team_member (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  team_id       BIGINT      NOT NULL,
  tournament_id BIGINT      NOT NULL,
  member_id     BIGINT      NOT NULL,
  position      INT         NOT NULL COMMENT '队内站位 0/1/2',
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_member (team_id, member_id),
  UNIQUE KEY uk_tournament_member (tournament_id, member_id),
  KEY idx_tmem_member (member_id),
  CONSTRAINT fk_tmem_team FOREIGN KEY (team_id) REFERENCES tournament_team (id) ON DELETE CASCADE,
  CONSTRAINT fk_tmem_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE,
  CONSTRAINT fk_tmem_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 对阵场次：round_no 从 1 开始（1=首轮，越靠后越深），slot 为该轮场次序号。
-- 首轮主客队直接落种子队；后续轮靠 home_from_match_id / away_from_match_id 指明晋级来源，
-- 上一场确认胜者后由后端自动带入对应槽位（不靠前端拼接）。
-- 状态 PENDING 待开赛 → ONGOING 进行中 → AWAIT_CONFIRM 待确认胜者 → CONFIRMED 已确认。
-- 阶段 stage：REGULATION 规定局 / SHOOTOFF 加赛箭。
CREATE TABLE IF NOT EXISTS tournament_match (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  tournament_id        BIGINT      NOT NULL,
  round_no             INT         NOT NULL,
  slot                 INT         NOT NULL,
  match_no             INT         NOT NULL COMMENT '场次号：全赛事按轮次统一编号',
  home_team_id         BIGINT      NULL,
  away_team_id         BIGINT      NULL,
  winner_team_id       BIGINT      NULL,
  status               VARCHAR(16) NOT NULL,
  stage                VARCHAR(16) NOT NULL DEFAULT 'REGULATION',
  home_score           INT         NOT NULL DEFAULT 0 COMMENT '规定局主队总分',
  away_score           INT         NOT NULL DEFAULT 0,
  home_x_count         INT         NOT NULL DEFAULT 0,
  away_x_count         INT         NOT NULL DEFAULT 0,
  shootoff_round_count INT         NOT NULL DEFAULT 0 COMMENT '已进行的加赛轮数',
  home_from_match_id   BIGINT      NULL COMMENT '主队晋级来源场次',
  away_from_match_id   BIGINT      NULL COMMENT '客队晋级来源场次',
  confirmed_by         VARCHAR(32) NULL,
  confirmed_at         DATETIME    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_round_slot (tournament_id, round_no, slot),
  KEY idx_match_tournament (tournament_id),
  KEY idx_match_home_team (home_team_id),
  KEY idx_match_away_team (away_team_id),
  CONSTRAINT fk_match_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 局：确认后不可改写；撤回即物理删除最后一局并在 tournament_log 留撤回人/时间/原因
CREATE TABLE IF NOT EXISTS match_end (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  match_id      BIGINT      NOT NULL,
  end_no        INT         NOT NULL COMMENT '局号，从 1 连续',
  home_score    INT         NOT NULL,
  away_score    INT         NOT NULL,
  home_x_count  INT         NOT NULL,
  away_x_count  INT         NOT NULL,
  status        VARCHAR(12) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 录入中 / CONFIRMED 已确认',
  confirmed_by  VARCHAR(32) NOT NULL,
  confirmed_at  DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_end (match_id, end_no),
  KEY idx_end_match (match_id),
  CONSTRAINT fk_end_match FOREIGN KEY (match_id) REFERENCES tournament_match (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 局内箭值：唯一键 (end_id, member_id, shot_index)，未确认局可就地覆盖更正
CREATE TABLE IF NOT EXISTS match_arrow (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  end_id      BIGINT      NOT NULL,
  match_id    BIGINT      NOT NULL,
  team_id     BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  shot_index  INT         NOT NULL COMMENT '该局该队员第几支，0..arrows_per_end-1',
  ring        VARCHAR(4)  NOT NULL COMMENT 'X/10..1/M',
  ring_value  INT         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_end_member_shot (end_id, member_id, shot_index),
  KEY idx_arrow_match (match_id),
  KEY idx_arrow_member (member_id),
  CONSTRAINT fk_arrow_end FOREIGN KEY (end_id) REFERENCES match_end (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 加赛轮：一轮每名队员各射一支；先比加赛总分，再比 X 数，仍平则开下一轮
CREATE TABLE IF NOT EXISTS shootoff_round (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  match_id     BIGINT      NOT NULL,
  round_no     INT         NOT NULL COMMENT '加赛轮号，从 1 连续',
  home_score   INT         NOT NULL DEFAULT 0,
  away_score   INT         NOT NULL DEFAULT 0,
  home_x_count INT         NOT NULL DEFAULT 0,
  away_x_count INT         NOT NULL DEFAULT 0,
  winner_team_id BIGINT    NULL COMMENT '本轮产生的胜者；NULL 表示仍平、需继续加赛',
  status       VARCHAR(12) NOT NULL COMMENT 'DRAFT 录入中 / LOCKED 已锁定',
  locked_by    VARCHAR(32) NULL,
  locked_at    DATETIME    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shootoff_match_round (match_id, round_no),
  KEY idx_shootoff_match (match_id),
  CONSTRAINT fk_shootoff_match FOREIGN KEY (match_id) REFERENCES tournament_match (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shootoff_arrow (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  round_id    BIGINT      NOT NULL,
  match_id    BIGINT      NOT NULL,
  team_id     BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  ring        VARCHAR(4)  NOT NULL,
  ring_value  INT         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shootoff_arrow (round_id, member_id),
  KEY idx_soarrow_match (match_id),
  CONSTRAINT fk_soarrow_round FOREIGN KEY (round_id) REFERENCES shootoff_round (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 完整操作轨迹：建队/换队/开赛/确认局/撤回局/锁定加赛轮/确认胜者等全程留痕
CREATE TABLE IF NOT EXISTS tournament_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tournament_id BIGINT       NOT NULL,
  match_id      BIGINT       NULL,
  action        VARCHAR(28)  NOT NULL,
  operator      VARCHAR(32)  NOT NULL,
  role          VARCHAR(16)  NOT NULL COMMENT 'MANAGER 值班经理 / REFEREE 裁判 / SYSTEM 系统',
  detail        VARCHAR(500) NULL,
  created_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_tlog_tournament (tournament_id),
  KEY idx_tlog_match (match_id),
  CONSTRAINT fk_tlog_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================= 模块八：弓种能力认证 =================
-- 认证规则（带版本）：同一弓种+射距为一条规则谱系（rule_code，如 RECURVE-50），
-- version 从 1 递增。规则调整 = 发布新版本，旧版本转 RETIRED：
-- 既不能改写已发出的认证历史（历史认证持有评定当时的规则快照），新申请也只能选 ACTIVE 版本。
CREATE TABLE IF NOT EXISTS cert_rule (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  rule_code        VARCHAR(24)   NOT NULL COMMENT '规则谱系：弓种-射距，如 RECURVE-50',
  version          INT           NOT NULL COMMENT '版本号，从 1 递增',
  bow_type         VARCHAR(16)   NOT NULL COMMENT '弓种：RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓',
  distance         INT           NOT NULL COMMENT '射距（米）：10/18/30/50',
  group_size       INT           NOT NULL COMMENT '每组箭数要求：6 或 12',
  min_rounds       INT           NOT NULL COMMENT '证据窗口内最少完成回合数',
  min_average      DECIMAL(4,2)  NOT NULL COMMENT '通过标准：证据窗口平均每支箭环值下限',
  validity_months  INT           NOT NULL COMMENT '认证通过后的有效期（月）',
  status           VARCHAR(16)   NOT NULL COMMENT 'ACTIVE 现行 / RETIRED 已停用',
  note             VARCHAR(200)  NULL COMMENT '规则说明（如标准上调原因）',
  created_by       VARCHAR(32)   NOT NULL COMMENT '发布教练',
  created_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_rule_version (rule_code, version),
  KEY idx_cert_rule_type (bow_type, distance)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 认证申请：教练选定会员、弓种与规则版本，划定有明确起止的证据窗口建立。
-- 状态机：PENDING 待复核 → APPROVED 已通过 / REJECTED 已驳回 / NEED_MORE 待补充证据；
-- NEED_MORE 可补充证据重新提交（回到 PENDING，eval_seq+1，旧评定保留）；
-- APPROVED 可被 WITHDRAWN 撤回，或被同一适用范围的新认证 SUPERSEDED 取代；
-- 过期不落库改状态：APPROVED 且 valid_until < 当前时间即视为已过期，任何入口都不得再当有效认证。
CREATE TABLE IF NOT EXISTS cert_application (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  app_no          VARCHAR(24)  NOT NULL COMMENT '申请号',
  member_id       BIGINT       NOT NULL,
  bow_type        VARCHAR(16)  NOT NULL COMMENT '申请弓种',
  distance        INT          NOT NULL COMMENT '申请射距（米）',
  window_start    DATETIME     NOT NULL COMMENT '证据窗口起',
  window_end      DATETIME     NOT NULL COMMENT '证据窗口止',
  status          VARCHAR(16)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / NEED_MORE / WITHDRAWN / SUPERSEDED',
  eval_seq        INT          NOT NULL DEFAULT 1 COMMENT '当前评定序号：补充证据重新评定 +1',
  reviewed_by     VARCHAR(32)  NULL COMMENT '复核教练',
  reviewed_at     DATETIME     NULL,
  review_note     VARCHAR(500) NULL COMMENT '复核意见；驳回 / 要求补充证据时必填',
  valid_from      DATETIME     NULL COMMENT '认证生效时间（复核通过）',
  valid_until     DATETIME     NULL COMMENT '认证有效期至',
  withdrawn_by    VARCHAR(32)  NULL,
  withdrawn_at    DATETIME     NULL,
  withdraw_reason VARCHAR(500) NULL,
  created_by      VARCHAR(32)  NOT NULL COMMENT '建立申请的教练',
  created_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_app_no (app_no),
  KEY idx_cert_app_member (member_id),
  KEY idx_cert_app_scope (member_id, bow_type, distance),
  CONSTRAINT fk_cert_app_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 评定记录（每次建立申请 / 补充证据后系统按规则快照评定一次）：
-- 完整保存当时选定的规则快照（弓种、箭数、最少回合、通过标准、有效期）与证据窗口，
-- 后续规则调整、窗口改选都不会改写历史评定。
CREATE TABLE IF NOT EXISTS cert_eval (
  id                   BIGINT        NOT NULL AUTO_INCREMENT,
  application_id       BIGINT        NOT NULL,
  eval_seq             INT           NOT NULL COMMENT '评定序号：首次 1，补充证据 +1',
  rule_id              BIGINT        NOT NULL COMMENT '评定使用的规则版本 id',
  rule_code            VARCHAR(24)   NOT NULL,
  rule_version         INT           NOT NULL,
  snap_group_size      INT           NOT NULL COMMENT '规则快照：每组箭数',
  snap_min_rounds      INT           NOT NULL COMMENT '规则快照：最少回合数',
  snap_min_average     DECIMAL(4,2)  NOT NULL COMMENT '规则快照：平均环下限',
  snap_validity_months INT           NOT NULL COMMENT '规则快照：有效期月数',
  window_start         DATETIME      NOT NULL,
  window_end           DATETIME      NOT NULL,
  round_count          INT           NOT NULL COMMENT '本次评定采信回合数',
  arrow_total          INT           NOT NULL COMMENT '证据箭支总数',
  total_score          INT           NOT NULL COMMENT '证据总环数',
  avg_score            DECIMAL(5,2)  NOT NULL COMMENT '证据平均环/支',
  pass_flag            TINYINT(1)    NOT NULL COMMENT '系统预评是否达到通过标准（待复核结果）',
  eval_message         VARCHAR(500)  NOT NULL COMMENT '预评说明：采信情况与达标判定',
  created_by           VARCHAR(32)   NOT NULL,
  created_at           DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_eval_seq (application_id, eval_seq),
  KEY idx_cert_eval_rule (rule_id),
  CONSTRAINT fk_cert_eval_app  FOREIGN KEY (application_id) REFERENCES cert_application (id),
  CONSTRAINT fk_cert_eval_rule FOREIGN KEY (rule_id) REFERENCES cert_rule (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 评定-回合：本次评定具体采信了哪些计分回合（必须 SUBMITTED、弓种/射距/箭数匹配且落在窗口内）
CREATE TABLE IF NOT EXISTS cert_eval_round (
  id        BIGINT NOT NULL AUTO_INCREMENT,
  eval_id   BIGINT NOT NULL,
  round_id  BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cert_eval_round (eval_id, round_id),
  KEY idx_cer_round (round_id),
  CONSTRAINT fk_cer_eval  FOREIGN KEY (eval_id) REFERENCES cert_eval (id),
  CONSTRAINT fk_cer_round FOREIGN KEY (round_id) REFERENCES rounds (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 状态轨迹：建立 / 系统预评 / 通过 / 驳回 / 要求补充 / 补充重评 / 撤回 / 被取代，全程留痕
CREATE TABLE IF NOT EXISTS cert_log (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  application_id BIGINT       NOT NULL,
  action         VARCHAR(24)  NOT NULL COMMENT 'CREATE / EVAL / APPROVE / REJECT / NEED_MORE / RESUBMIT / WITHDRAW / SUPERSEDE',
  operator       VARCHAR(32)  NOT NULL,
  role           VARCHAR(16)  NOT NULL COMMENT 'COACH 教练 / SYSTEM 认证系统',
  detail         VARCHAR(500) NULL,
  created_at     DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_cert_log_app (application_id),
  CONSTRAINT fk_cert_log_app FOREIGN KEY (application_id) REFERENCES cert_application (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- cert_rule：6 行（RECURVE-50 同时存在已停用 v1 与现行 v2，演示版本化规则）
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (1, 'RECURVE-10', 1, 'RECURVE', 10, 6, 1, 6.00, 12, 'ACTIVE', '反曲弓 10 米基础认证：窗口平均每支箭不低于 6 环', '张岩', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (2, 'RECURVE-30', 1, 'RECURVE', 30, 6, 2, 7.00, 12, 'ACTIVE', '反曲弓 30 米认证：窗口内至少 2 个完成回合，平均不低于 7 环', '张岩', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (3, 'RECURVE-50', 1, 'RECURVE', 50, 6, 1, 7.50, 12, 'RETIRED', '反曲弓 50 米认证旧版：平均下限 7.5 环（已被 v2 取代，历史认证仍按本快照）', '张岩', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (4, 'RECURVE-50', 2, 'RECURVE', 50, 6, 1, 8.00, 12, 'ACTIVE', '反曲弓 50 米认证现行版：平均环下限由 7.5 上调至 8.0', '张岩', '2026-09-18 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (5, 'COMPOUND-30', 1, 'COMPOUND', 30, 12, 1, 8.00, 12, 'ACTIVE', '复合弓 30 米认证：12 支组完成回合，平均不低于 8 环', '王铮', '2026-09-01 10:00:00');
INSERT INTO cert_rule (`id`, `rule_code`, `version`, `bow_type`, `distance`, `group_size`, `min_rounds`, `min_average`, `validity_months`, `status`, `note`, `created_by`, `created_at`) VALUES (6, 'TRADITIONAL-18', 1, 'TRADITIONAL', 18, 6, 1, 6.00, 12, 'ACTIVE', '传统弓 18 米认证：窗口平均每支箭不低于 6 环', '苏禾', '2026-09-01 10:00:00');

-- cert_application：3 行（一笔现行有效 / 一笔已过期 / 一笔已撤回，覆盖会员可用状态变化）
INSERT INTO cert_application (`id`, `app_no`, `member_id`, `bow_type`, `distance`, `window_start`, `window_end`, `status`, `eval_seq`, `reviewed_by`, `reviewed_at`, `review_note`, `valid_from`, `valid_until`, `withdrawn_by`, `withdrawn_at`, `withdraw_reason`, `created_by`, `created_at`) VALUES (1, 'C20260920001', 1, 'RECURVE', 50, '2026-09-19 00:00:00', '2026-09-19 23:59:59', 'APPROVED', 1, '李慕白', '2026-09-20 10:05:00', '证据达标，复核通过', '2026-09-20 10:05:00', '2027-09-20 10:05:00', NULL, NULL, NULL, '李慕白', '2026-09-20 09:40:00');
INSERT INTO cert_application (`id`, `app_no`, `member_id`, `bow_type`, `distance`, `window_start`, `window_end`, `status`, `eval_seq`, `reviewed_by`, `reviewed_at`, `review_note`, `valid_from`, `valid_until`, `withdrawn_by`, `withdrawn_at`, `withdraw_reason`, `created_by`, `created_at`) VALUES (2, 'C20250911001', 2, 'TRADITIONAL', 18, '2025-09-10 00:00:00', '2025-09-10 23:59:59', 'APPROVED', 1, '苏禾', '2025-09-11 11:00:00', '历史认证，到期需重新评定', '2025-09-11 11:00:00', '2026-09-11 11:00:00', NULL, NULL, NULL, '苏禾', '2025-09-11 10:30:00');
INSERT INTO cert_application (`id`, `app_no`, `member_id`, `bow_type`, `distance`, `window_start`, `window_end`, `status`, `eval_seq`, `reviewed_by`, `reviewed_at`, `review_note`, `valid_from`, `valid_until`, `withdrawn_by`, `withdrawn_at`, `withdraw_reason`, `created_by`, `created_at`) VALUES (3, 'C20260917001', 4, 'COMPOUND', 30, '2026-09-16 00:00:00', '2026-09-16 23:59:59', 'WITHDRAWN', 1, '王铮', '2026-09-17 09:30:00', '证据达标，复核通过', '2026-09-17 09:30:00', '2027-09-17 09:30:00', '王铮', '2026-09-25 15:00:00', '抽查发现证据窗口与实际训练记录不符，撤回并要求重新评定', '王铮', '2026-09-17 09:00:00');

-- cert_eval：每笔申请一次评定，规则快照随评定固化
INSERT INTO cert_eval (`id`, `application_id`, `eval_seq`, `rule_id`, `rule_code`, `rule_version`, `snap_group_size`, `snap_min_rounds`, `snap_min_average`, `snap_validity_months`, `window_start`, `window_end`, `round_count`, `arrow_total`, `total_score`, `avg_score`, `pass_flag`, `eval_message`, `created_by`, `created_at`) VALUES (1, 1, 1, 3, 'RECURVE-50', 1, 6, 1, 7.50, 12, '2026-09-19 00:00:00', '2026-09-19 23:59:59', 1, 6, 58, 9.67, 1, '证据窗口内 1 个回合共 6 支箭，总环 58，平均 9.67 环/支；通过标准：≥1 回合且平均 ≥7.50，系统预评达标', '李慕白', '2026-09-20 09:40:00');
INSERT INTO cert_eval (`id`, `application_id`, `eval_seq`, `rule_id`, `rule_code`, `rule_version`, `snap_group_size`, `snap_min_rounds`, `snap_min_average`, `snap_validity_months`, `window_start`, `window_end`, `round_count`, `arrow_total`, `total_score`, `avg_score`, `pass_flag`, `eval_message`, `created_by`, `created_at`) VALUES (2, 2, 1, 6, 'TRADITIONAL-18', 1, 6, 1, 6.00, 12, '2025-09-10 00:00:00', '2025-09-10 23:59:59', 1, 6, 40, 6.67, 1, '证据窗口内 1 个回合共 6 支箭，总环 40，平均 6.67 环/支；通过标准：≥1 回合且平均 ≥6.00，系统预评达标', '苏禾', '2025-09-11 10:30:00');
INSERT INTO cert_eval (`id`, `application_id`, `eval_seq`, `rule_id`, `rule_code`, `rule_version`, `snap_group_size`, `snap_min_rounds`, `snap_min_average`, `snap_validity_months`, `window_start`, `window_end`, `round_count`, `arrow_total`, `total_score`, `avg_score`, `pass_flag`, `eval_message`, `created_by`, `created_at`) VALUES (3, 3, 1, 5, 'COMPOUND-30', 1, 12, 1, 8.00, 12, '2026-09-16 00:00:00', '2026-09-16 23:59:59', 1, 12, 112, 9.33, 1, '证据窗口内 1 个回合共 12 支箭，总环 112，平均 9.33 环/支；通过标准：≥1 回合且平均 ≥8.00，系统预评达标', '王铮', '2026-09-17 09:00:00');

-- cert_eval_round：三笔评定分别采信回合 6 / 13 / 3
INSERT INTO cert_eval_round (`id`, `eval_id`, `round_id`) VALUES (1, 1, 6);
INSERT INTO cert_eval_round (`id`, `eval_id`, `round_id`) VALUES (2, 2, 13);
INSERT INTO cert_eval_round (`id`, `eval_id`, `round_id`) VALUES (3, 3, 3);

-- cert_log：建立 / 系统预评 / 复核结论全程留痕
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (1, 1, 'CREATE', '李慕白', 'COACH', '建立认证申请：陈子昂 · 反曲弓 50 米 · 规则 RECURVE-50 v1；证据窗口 2026-09-19 00:00 ~ 2026-09-19 23:59', '2026-09-20 09:40:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (2, 1, 'EVAL', '认证系统', 'SYSTEM', '系统预评达标：1 回合 6 支箭平均 9.67 环（标准 ≥7.50），提交复核', '2026-09-20 09:40:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (3, 1, 'APPROVE', '李慕白', 'COACH', '复核通过：认证 2026-09-20 生效，有效期至 2027-09-20；适用范围：反曲弓 50 米及以内射距', '2026-09-20 10:05:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (4, 2, 'CREATE', '苏禾', 'COACH', '建立认证申请：林悦 · 传统弓 18 米 · 规则 TRADITIONAL-18 v1；证据窗口 2025-09-10 00:00 ~ 2025-09-10 23:59', '2025-09-11 10:30:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (5, 2, 'EVAL', '认证系统', 'SYSTEM', '系统预评达标：1 回合 6 支箭平均 6.67 环（标准 ≥6.00），提交复核', '2025-09-11 10:30:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (6, 2, 'APPROVE', '苏禾', 'COACH', '复核通过：认证 2025-09-11 生效，有效期至 2026-09-11；适用范围：传统弓 18 米及以内射距', '2025-09-11 11:00:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (7, 3, 'CREATE', '王铮', 'COACH', '建立认证申请：孙嘉 · 复合弓 30 米 · 规则 COMPOUND-30 v1；证据窗口 2026-09-16 00:00 ~ 2026-09-16 23:59', '2026-09-17 09:00:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (8, 3, 'EVAL', '认证系统', 'SYSTEM', '系统预评达标：1 回合 12 支箭平均 9.33 环（标准 ≥8.00），提交复核', '2026-09-17 09:00:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (9, 3, 'APPROVE', '王铮', 'COACH', '复核通过：认证 2026-09-17 生效，有效期至 2027-09-17；适用范围：复合弓 30 米及以内射距', '2026-09-17 09:30:00');
INSERT INTO cert_log (`id`, `application_id`, `action`, `operator`, `role`, `detail`, `created_at`) VALUES (10, 3, 'WITHDRAW', '王铮', 'COACH', '撤回认证：抽查发现证据窗口与实际训练记录不符，撤回并要求重新评定', '2026-09-25 15:00:00');
