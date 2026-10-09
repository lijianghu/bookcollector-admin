package com.bookcollector.publish;

import com.bookcollector.publish.entity.PublishJob;
import com.bookcollector.publish.service.PublishService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * AI 书籍解读的 cron 触发器。
 *
 * <h3>为什么用 {@link SchedulingConfigurer} 而不是 {@code @Scheduled(cron = "...")}</h3>
 * 同 {@code RefreshScheduler}：{@code @Scheduled} 的 cron 是编译期常量，
 * 改一次配置就得重启后端，而 cron 是用户要在页面上改的。
 *
 * <p>做法：注册一个 {@link Trigger}，它的
 * {@link Trigger#nextExecutionTime(TriggerContext)} 被调用时才去读当前 cron，
 * 所以改完配置<b>不需要重启</b>。
 *
 * <h3>🔴 为什么 Trigger 不返回 null 来表示「未启用」</h3>
 * {@code ReschedulingRunnable} 拿到 null 之后<b>不会再排下一次</b> —— 这个任务就永久死掉了。
 * 于是「先停用、再启用」会让它再也跑不起来，而页面上看不出任何异常。
 * 所以：Trigger <b>永远</b>算出下一次时间（哪怕未启用），
 * 「是否真的要跑」交给 {@link PublishService#runScheduled()} 在运行时判断。
 *
 * <h3>⚠️ 与定时刷新共用一条调度线程</h3>
 * 本应用有<b>两个</b> {@code SchedulingConfigurer}（本类与 {@code RefreshScheduler}），
 * 都用 Spring 为 {@code @EnableScheduling} 建的默认<b>单线程</b>调度器。
 * 所以 {@link PublishService#runScheduled()} 必须是「丢线程池后立刻返回」，
 * 不能就地跑一本（那要 1~3 分钟）—— 否则会把它之后的
 * {@code RefreshScheduler} 的 {@code nextExecutionTime()} 一起推迟，
 * 表现为「定时刷新莫名其妙晚了几分钟触发」。
 */
@Component
public class PublishScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PublishScheduler.class);

    private final PublishService publishService;

    public PublishScheduler(PublishService publishService) {
        this.publishService = publishService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(new Runnable() {
            @Override
            public void run() {
                publishService.runScheduled();
            }
        }, new Trigger() {
            @Override
            public Date nextExecutionTime(TriggerContext triggerContext) {
                String cron = publishService.currentCron();
                try {
                    return new CronTrigger(cron).nextExecutionTime(triggerContext);
                } catch (RuntimeException e) {
                    // 走到这里说明 cron 虽然格式合法，但算不出下一次（如「2 月 30 日」）。
                    // 退回默认表达式，绝不让 Trigger 返回 null（见类注释）
                    log.warn("AI 书籍解读：cron「{}」算不出下一次触发时间（{}），本次改用默认 {}",
                            cron, e.getMessage(), PublishJob.DEFAULT_CRON);
                    return new CronTrigger(PublishJob.DEFAULT_CRON).nextExecutionTime(triggerContext);
                }
            }
        });
        log.info("AI 书籍解读触发器已注册：每次触发前重新读取 cron，改配置无需重启后端");
    }
}
