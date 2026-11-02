package com.gamerin.backend.domain.report.entity;

/**
 * 신고 접수 시점의 단계. 같은 신고자가 같은 대상에 대해 단계별로 1건씩 신고할 수 있다.
 * (신청 건 신고: 진행 단계에서 반려된 뒤에도 종료 보고(FINISHED) 단계에서 다시 이의제기할 수 있어야 한다.)
 * 신청 건 외 대상(게시글, 댓글, 유저, 메시지, 프로그램 직접 신고)은 항상 GENERAL이다.
 */
public enum ReportStage {
    GENERAL,
    ONGOING,
    FINISHED
}
