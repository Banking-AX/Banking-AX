-- 선택 사항: 현재 ERD에는 업무 코드 마스터 테이블이 없습니다.
-- 아래는 독립적인 참조용 테이블 신설 제안이며 기존 DDL에는 없는 변경입니다.
CREATE TABLE IF NOT EXISTS task_catalog (
  task_code VARCHAR(40) NOT NULL PRIMARY KEY,
  category CHAR(1) NOT NULL,
  task_name VARCHAR(120) NOT NULL,
  processing_type VARCHAR(30) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  description VARCHAR(255) NULL
);
INSERT INTO task_catalog(task_code,category,task_name,processing_type,is_active,description) VALUES
('A-01','A','거래내역 조회','QUERY',1,'계좌 거래내역 조건 검색'),
('A-02','A','대출계좌 정보 조회','QUERY',1,'대출계좌 목록 및 상세정보 조회'),
('B-01','B','채무변제 충당순서 변경','AUTO_PROCESS',1,'서류와 고객 동의 및 백엔드 조건 검증 후 모의 처리'),
('B-02','B','청년 주택드림 청약통장 해지','AUTO_PROCESS',1,'프로젝트 PoC 비대면 모의 해지'),
('C-01','C','채무조정 사전접수','STAFF_HANDOFF',1,'사전접수 후 직원 검토'),
('C-02','C','상속예금 해지 및 지급 사전접수','STAFF_HANDOFF',1,'사전접수 후 직원 검토');
-- 실제 물리 FK를 추가할지는 팀 결정 필요.
