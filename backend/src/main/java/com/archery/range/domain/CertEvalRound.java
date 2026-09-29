package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 评定-回合连接表：某次评定具体采信了哪些计分回合。
 * 只有 SUBMITTED、弓种 / 射距 / 每组箭数匹配且落在证据窗口内的回合才会进入本表。
 */
@Entity
@Table(name = "cert_eval_round")
public class CertEvalRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "eval_id", nullable = false)
    private Long evalId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    public CertEvalRound() {
    }

    public CertEvalRound(Long evalId, Long roundId) {
        this.evalId = evalId;
        this.roundId = roundId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEvalId() {
        return evalId;
    }

    public void setEvalId(Long evalId) {
        this.evalId = evalId;
    }

    public Long getRoundId() {
        return roundId;
    }

    public void setRoundId(Long roundId) {
        this.roundId = roundId;
    }
}
