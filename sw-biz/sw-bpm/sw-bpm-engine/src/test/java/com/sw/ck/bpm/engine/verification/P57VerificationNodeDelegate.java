package com.sw.ck.bpm.engine.verification;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** p57-evidence 隔离节点的可观察运行结果。 */
@Profile("p57-evidence")
@Component("p57VerificationNodeDelegate")
public class P57VerificationNodeDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        execution.setVariable("p57VerificationResult", "OBSERVED");
    }
}
