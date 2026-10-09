package com.bookcollector.refresh;

import com.bookcollector.refresh.entity.RefreshJob;
import com.bookcollector.refresh.service.RefreshService;

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
 * 定时刷新的 cron 触发器。
 *
 * <h3>为什么用 {@link SchedulingConfigurer} 而不是 {@code @Scheduled(cron = "...")}</h3>
 * {@code @Scheduled} 的 cron 是<b>编译期常量</b>（注解属性不支持 SpEL 之外的动态值），
 * 改一次配置就得重启后端。而 cron 是用户要在页面上改的 —— 这正是本类的存在理由。
 *
 * <p>做法：注册一个 {@link Trigger}，它的 {@link Trigger#nextExecutionTime(TriggerContext)}
 * 被调用时<b>才去读当前 cron</b>。所以改完配置<b>不需要重启</b>，
 * 下一次触发就按新表达式算。
 *
 * <h3>🔴 为什么 Trigger 不返回 null 来表示「未启用」</h3>
 * {@code ReschedulingRunnable} 拿到 null 之后<b>不会再排下一次</b> ——
 * 这个任务就永久死掉了。于是「先停用、再启用」会让定时刷新再也跑不起来，
 * 而页面上看不出任何异常。
 *
 * <p>所以：Trigger <b>永远</b>算出下一次时间（哪怕未启用），
 * 「是否真的要跑」交给 {@link RefreshService#runScheduled()} 在运行时判断。
 *
 * <h3>为什么不需要自己管线程池</h3>
 * 本应用的定时任务都是「注册 Trigger」这种轻量形态（本类与 {@code PublishScheduler}），
 * 每次触发只做一次判断 + 一次提交，真正的重活在自己模块的线程池里跑。
 * 所以 Spring 默认给 {@code @EnableScheduling} 建的<b>单线程</b>调度器完全够用，
 * 而且它由 Spring 负责创建与关闭 —— 自己 new 一个 {@code ThreadPoolTaskScheduler}
 * 反而要手写 {@code initialize()} / {@code destroy()}，还要处理销毁顺序。
 *
 * <p>⚠️ 正因为是<b>单线程</b>：任何 {@code Trigger} 任务体都<b>不能</b>就地跑长任务，
 * 否则会把它之后的其它 Trigger 的 {@code nextExecutionTime()} 一起推迟。
 * （{@code PublishService.runScheduled()} 就是按这条规则写成「丢线程池后立刻返回」的。）
 */
@Component
public class RefreshScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshService refreshService;

    public RefreshScheduler(RefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(new Runnable() {
            @Override
            public void run() {
                refreshService.runScheduled();
            }
        }, new Trigger() {
            @Override
            public Date nextExecutionTime(TriggerContext triggerContext) {
                String cron = refreshService.currentCron();
                try {
                    return new CronTrigger(cron).nextExecutionTime(triggerContext);
                } catch (RuntimeException e) {
                    // 走到这里说明 cron 虽然格式合法，但算不出下一次（如「2 月 30 日」）。
                    // 退回默认表达式，绝不让 Trigger 返回 null（见类注释）
                    log.warn("定时刷新：cron「{}」算不出下一次触发时间（{}），本次改用默认 {}",
                            cron, e.getMessage(), RefreshJob.DEFAULT_CRON);
                    return new CronTrigger(RefreshJob.DEFAULT_CRON).nextExecutionTime(triggerContext);
                }
            }
        });
        log.info("定时刷新触发器已注册：每次触发前重新读取 cron，改配置无需重启后端");
    }
}
