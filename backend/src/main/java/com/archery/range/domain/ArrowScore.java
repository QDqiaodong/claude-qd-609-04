package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 单支箭的成绩：环数标记（X / 10 / 9 ... / M 脱靶）与计分环值。
 * 它本身不持有顺序，顺序由 Round 的 @OrderColumn(name = "shot_index") 维护。
 */
@Entity
@Table(name = "arrow_score")
public class ArrowScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 环数标记：X / 10 / 9 / 8 / 7 / 6 / 5 / 4 / 3 / 2 / 1 / M */
    @Column(nullable = false, length = 4)
    private String ring;

    /** 计分环值：X 与 10 都记 10 分，M 记 0 分 */
    @Column(name = "ring_value", nullable = false)
    private Integer ringValue;

    public ArrowScore() {
    }

    public ArrowScore(String ring, Integer ringValue) {
        this.ring = ring;
        this.ringValue = ringValue;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRing() {
        return ring;
    }

    public void setRing(String ring) {
        this.ring = ring;
    }

    public Integer getRingValue() {
        return ringValue;
    }

    public void setRingValue(Integer ringValue) {
        this.ringValue = ringValue;
    }
}
