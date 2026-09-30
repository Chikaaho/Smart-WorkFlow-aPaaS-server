package com.sw.ck.form.txn.port;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.FormTxnActionPort;
import com.sw.ck.form.txn.model.TxnActionView;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link FormTxnActionPort} 实现：直接委托首阶段交付的事务内核
 * （{@link TxnActionExecutor}/{@link TxnActionService}），不复制动作逻辑。
 * <p>
 * 授权口径（P62 分级执行合同：节点调用不得因绕过 HTTP 控制器而放宽授权）：
 * 运行期要求已还原的可信登录身份，且具备 {@code form:action:invoke} 权限
 * （超管按其语义旁路）；动作的租户归属由租户拦截器与动作加载路径强制
 * （跨租户动作不可达，返回不存在语义）。发布期绑定校验同样要求该权限。
 * </p>
 */
@Service
public class FormTxnActionPortImpl implements FormTxnActionPort {

    private static final Logger log = LoggerFactory.getLogger(FormTxnActionPortImpl.class);

    /** 与 TxnActionController 相同的方法权限码：节点调用沿用同一授权口径。 */
    static final String INVOKE_PERMISSION = "form:action:invoke";

    private final TxnActionService actionService;
    private final TxnActionExecutor executor;

    public FormTxnActionPortImpl(TxnActionService actionService, TxnActionExecutor executor) {
        this.actionService = actionService;
        this.executor = executor;
    }

    @Override
    public TxnActionResult invoke(TxnActionCommand command) {
        LoginUser operator = requireAuthorizedOperator();
        if (command == null || command.actionId() == null || command.actionId().isBlank()) {
            throw new BaseException(FormErrorCode.ACTION_NOT_FOUND, "动作标识不能为空");
        }
        if (command.invocationKey() == null || command.invocationKey().isBlank()) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                    "内部调用必须携带稳定幂等键（如 NODE:{instanceId}:{nodeId}）");
        }
        TxnInvokeRequest request = new TxnInvokeRequest();
        request.setRecordId(command.recordId());
        request.setQuantity(command.quantity());
        request.setInvocationKey(command.invocationKey());
        request.setExpectedVersion(command.expectedVersion());
        request.setReservationId(command.reservationId());
        request.setActionVersion(command.actionVersion());
        log.info("事务动作受控调用: actionId={}, operator={}, tenant={}, key={}",
                command.actionId(), operator.getUserId(), operator.getTenantId(), command.invocationKey());
        TxnInvokeResult result = executor.invoke(command.actionId(), request);
        return new TxnActionResult(result.invocationId(), result.status(), result.actionVersion(),
                result.reservationId(), result.quantity(), result.balanceAfter(), result.reservedAfter(),
                result.errorCode(), result.errorMsg(), result.durationMs(), result.replay());
    }

    @Override
    public Optional<TxnActionDescriptor> describe(String actionId) {
        requireAuthorizedOperator();
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        TxnActionView view;
        try {
            view = actionService.get(actionId);
        } catch (BaseException e) {
            // 不存在或跨租户不可达：发布期绑定校验按 empty 处理（不泄露他租户动作信息）
            return Optional.empty();
        }
        if (view == null) {
            return Optional.empty();
        }
        return Optional.of(new TxnActionDescriptor(view.id(), view.formId(), view.actionKey(),
                view.actionType(), view.status(), view.currentVersion()));
    }

    private LoginUser requireAuthorizedOperator() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        boolean allowed = user.isSuperAdmin()
                || (user.getPermissions() != null && user.getPermissions().contains(INVOKE_PERMISSION));
        if (!allowed) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "无权调用表单事务动作：缺少 " + INVOKE_PERMISSION);
        }
        return user;
    }
}
