package com.sw.ck.bpm.process.runner;

import com.sw.ck.bpm.api.facade.BpmDeployFacade;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.common.constant.CommonConstants;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时部署 BPMN 并插入表单↔流程绑定（仅 dev/test 骨架初始化资产）。
 * <p>
 * 职责：
 * <ol>
 *   <li>部署 {@code skeleton_approval.bpmn20.xml}（单节点审批）</li>
 *   <li>检查并插入 {@code it_application} → {@code skeleton_approval} 的启用绑定</li>
 * </ol>
 * </p>
 *
 * <p>
 * I5 收口：骨架部署与绑定是显式 dev/test 初始化资产，生产 profile 不再启动即以
 * 租户 0 创建可被业务依赖的定义或绑定；真实租户必须发布本租户定义并建立本租户绑定。
 * </p>
 *
 * <p>
 * 部署时手动设置 {@link LoginUserHolder} 以确保 MyBatis-Plus 拦截器
 * （审计字段填充 + 租户行级隔离）在插入绑定时正确生效。
 * </p>
 *
 * <p>
 * 通过 {@link BpmDeployFacade} 间接部署 BPMN，不再直接 import Flowable RepositoryService。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.bpm", name = "enabled", havingValue = "true")
public class BpmDeployRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BpmDeployRunner.class);

    /** 骨架流程定义 key */
    static final String PROCESS_KEY = "skeleton_approval";

    /** BPMN 类路径 */
    private static final String BPMN_RESOURCE = "processes/" + PROCESS_KEY + ".bpmn20.xml";

    /** IT申请 formKey（以 form 模块种子数据为准） */
    static final String IT_APPLICATION_FORM_KEY = "it_application";

    private final BpmDeployFacade bpmDeployFacade;
    private final BpmFormBindingService bindingService;
    private final Environment environment;

    public BpmDeployRunner(BpmDeployFacade bpmDeployFacade,
                           BpmFormBindingService bindingService,
                           Environment environment) {
        this.bpmDeployFacade = bpmDeployFacade;
        this.bindingService = bindingService;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        if (!isDevelopmentOnly()) {
            log.info("非 dev/test profile，跳过骨架流程部署与绑定（生产租户定义经正式发布链创建）");
            return;
        }
        LoginUser systemUser = new LoginUser();
        systemUser.setUserId(CommonConstants.SYSTEM_OPERATOR_ID);
        systemUser.setTenantId(Long.parseLong(CommonConstants.SUPER_TENANT_ID));
        LoginUserHolder.set(systemUser);
        try {
            deployProcess();
            bindFormToProcess();
        } catch (Exception e) {
            log.error("启动部署/绑定失败，请确认 BPMN 资源和数据库状态", e);
        } finally {
            LoginUserHolder.clear();
        }
    }

    /** 与 DebugAuthenticationProfile 同口径：仅纯 dev/test profile 视为开发环境。 */
    private boolean isDevelopmentOnly() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            return false;
        }
        for (String profile : activeProfiles) {
            if (!"dev".equals(profile) && !"test".equals(profile)) {
                return false;
            }
        }
        return true;
    }

    /** 部署 BPMN（经 BpmDeployFacade，不再直接调 RepositoryService） */
    private void deployProcess() {
        // deployClasspathBpmn 当前契约恒 present，部署失败抛异常
        String deploymentId = bpmDeployFacade.deployClasspathBpmn(BPMN_RESOURCE, PROCESS_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "BPMN 部署未返回部署 ID: processKey=" + PROCESS_KEY));
        log.info("BPMN 部署完成: processKey={}, deploymentId={}", PROCESS_KEY, deploymentId);
    }

    /** 插入 IT申请 → skeleton_approval 绑定（若已存在则跳过） */
    private void bindFormToProcess() {
        List<BpmFormBinding> existing = bindingService.findActiveByFormKey(IT_APPLICATION_FORM_KEY);
        if (!existing.isEmpty()) {
            log.info("绑定已存在: formKey={} → processDefKey={}, 跳过",
                    IT_APPLICATION_FORM_KEY, existing.get(0).getProcessDefKey());
            return;
        }

        BpmFormBinding binding = new BpmFormBinding();
        binding.setFormKey(IT_APPLICATION_FORM_KEY);
        binding.setProcessDefKey(PROCESS_KEY);
        binding.setActive(true);
        bindingService.save(binding);
        log.info("绑定已创建: formKey={} → processDefKey={}", IT_APPLICATION_FORM_KEY, PROCESS_KEY);
    }
}
