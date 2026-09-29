package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * 计分回合：一组 6 支或 12 支箭。
 *
 * 本项目的核心数据访问路线 —— 有序集合映射：
 * {@code @OneToMany + @JoinTable + @OrderColumn(name = "shot_index")}。
 * Hibernate 在 round_arrow 这张连接表里维护 shot_index，
 * 因此 arrows 列表的下标就是「第几支箭」，append 后 save 即可，顺序不会乱。
 */
@Entity
@Table(name = "rounds")
public class Round {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_no", nullable = false, unique = true, length = 24)
    private String roundNo;

    /**
     * 关联会员 / 箭道用 @NotFound(IGNORE)：历史库里可能残留外键目标缺失的损坏回合，
     * 这类记录必须能被加载并明确列为「无法参与比较」，而不是在加载整个回合列表时炸掉。
     * 它们的原始成绩保留、不静默归入任何组（见 PersonalBestSupport.check）。
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "member_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Member member;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "lane_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Lane lane;

    /** 本回合使用弓种：RECURVE 反曲弓 / COMPOUND 复合弓 / TRADITIONAL 传统弓（认证证据字段） */
    @Column(name = "bow_type", nullable = false, length = 16)
    private String bowType;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** 完成时间：提交那一刻落定；进行中 / 暂停回合为 NULL。同组同分平手时取它更早的回合为最好 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 一组箭支数：6 或 12 */
    @Column(name = "arrow_count", nullable = false)
    private Integer arrowCount;

    @Column(name = "total_score", nullable = false)
    private Integer totalScore;

    @Column(name = "average_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal averageScore;

    @Column(name = "personal_best", nullable = false)
    private Boolean personalBest;

    /** ONGOING 进行中 / SUBMITTED 已提交 */
    @Column(nullable = false, length = 16)
    private String status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinTable(
            name = "round_arrow",
            joinColumns = @JoinColumn(name = "round_id"),
            inverseJoinColumns = @JoinColumn(name = "arrow_id"))
    @OrderColumn(name = "shot_index")
    private List<ArrowScore> arrows = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(String roundNo) {
        this.roundNo = roundNo;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public Lane getLane() {
        return lane;
    }

    public void setLane(Lane lane) {
        this.lane = lane;
    }

    public String getBowType() {
        return bowType;
    }

    public void setBowType(String bowType) {
        this.bowType = bowType;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getArrowCount() {
        return arrowCount;
    }

    public void setArrowCount(Integer arrowCount) {
        this.arrowCount = arrowCount;
    }

    public Integer getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Integer totalScore) {
        this.totalScore = totalScore;
    }

    public BigDecimal getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(BigDecimal averageScore) {
        this.averageScore = averageScore;
    }

    public Boolean getPersonalBest() {
        return personalBest;
    }

    public void setPersonalBest(Boolean personalBest) {
        this.personalBest = personalBest;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<ArrowScore> getArrows() {
        return arrows;
    }

    public void setArrows(List<ArrowScore> arrows) {
        this.arrows = arrows;
    }
}
