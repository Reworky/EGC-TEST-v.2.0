package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Журнал автопубликации в Threads (ThreadsPublishService): одна запись на пост из threads_posts.json.
 *  Запись создаётся ДО обращения к API (статус PENDING), поэтому после сбоя пост не уйдёт дважды. */
@Getter
@Setter
@Entity
@Table(name = "threads_post_log")
public class ThreadsPostLog {

    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_key", nullable = false, unique = true, length = 64)
    private String postKey;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    /** Id опубликованного поста в Threads. */
    @Column(length = 64)
    private String threadsId;

    /** Ответ со ссылкой ещё нужно опубликовать (уходит со следующего тика, через ~5 минут). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean replyPending = false;

    @Column(length = 500)
    private String error;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime publishedAt;
}
