package ru.gamebot.platform.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.service.ErrorMonitorService;

/** Подключает к корневому логгеру logback appender, который передаёт WARN/ERROR в {@link ErrorMonitorService}.
 * Любой сбой здесь не должен мешать запуску бота - при проблеме монитор просто остаётся пустым. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorMonitorInstaller {

    private final ErrorMonitorService monitor;

    /** Тексты всех причин цепочки исключения: «Failed to send message» сам по себе не говорит, что это заблокировавший бота игрок, - это видно только в причине. */
    private static String causeChain(IThrowableProxy thrown) {
        StringBuilder sb = new StringBuilder();
        IThrowableProxy c = thrown == null ? null : thrown.getCause();
        for (int i = 0; c != null && i < 8; i++, c = c.getCause()) {
            if (c.getMessage() != null) sb.append(c.getMessage()).append(' ');
        }
        return sb.toString();
    }

    @PostConstruct
    public void install() {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                log.warn("ErrorMonitor: logback не обнаружен, монитор ошибок отключён");
                return;
            }
            AppenderBase<ILoggingEvent> appender = new AppenderBase<ILoggingEvent>() {
                @Override
                protected void append(ILoggingEvent event) {
                    try {
                        if (event.getLevel().toInt() < Level.WARN_INT) return;
                        IThrowableProxy thrown = event.getThrowableProxy();
                        monitor.recordLog(event.getLevel().toString(), event.getLoggerName(), event.getFormattedMessage(),
                                thrown != null ? thrown.getClassName() : null, thrown != null ? thrown.getMessage() : null, causeChain(thrown));
                    } catch (Throwable ignored) {
                        // логирование не должно ломать логирование
                    }
                }
            };
            appender.setContext(context);
            appender.setName("ERROR_MONITOR");
            appender.start();
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        } catch (Throwable e) {
            log.warn("ErrorMonitor: не удалось подключить перехват логов: {}", e.getMessage());
        }
    }
}
