package org.zstack.network.service.virtualrouter.vyos;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.appliancevm.ApplianceVmConstant;
import org.zstack.appliancevm.ApplianceVmSpec;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.core.Completion;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.rest.JsonAsyncRESTCallback;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.vm.VmInstanceConstant;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.vm.VmNicInventory;
import org.zstack.network.service.virtualrouter.VirtualRouterCommands.InitCommand;
import org.zstack.network.service.virtualrouter.VirtualRouterCommands.InitRsp;
import org.zstack.network.service.virtualrouter.VirtualRouterConstant;
import org.zstack.network.service.virtualrouter.VirtualRouterGlobalConfig;
import org.zstack.network.service.virtualrouter.VirtualRouterManager;
import org.zstack.network.service.virtualrouter.VirtualRouterVmInventory;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.zstack.core.Platform.operr;

/**
 * Created by shixin.ruan on 2018/05/22.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VyosGetVersionFlow extends NoRollbackFlow {
    private final static CLogger logger = Utils.getLogger(VyosGetVersionFlow.class);
    @Autowired
    private VyosManager vyosManager;
    @Autowired
    protected RESTFacade restf;
    @Autowired
    private VirtualRouterManager vrMgr;

    @Override
    public void run(FlowTrigger flowTrigger, Map flowData) {
        final VirtualRouterVmInventory vr = (VirtualRouterVmInventory) flowData.get(VirtualRouterConstant.Param.VR.toString());
        String vrUuid;
        VmNicInventory mgmtNic;
        if (vr != null) {
            mgmtNic = vr.getManagementNic();
            vrUuid = vr.getUuid();
        } else {
            final VmInstanceSpec spec = (VmInstanceSpec) flowData.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
            final ApplianceVmSpec aspec = spec.getExtensionData(ApplianceVmConstant.Params.applianceVmSpec.toString(), ApplianceVmSpec.class);
            mgmtNic = spec.getDestNics().stream().filter(n->n.getL3NetworkUuid().equals(aspec.getManagementNic().getL3NetworkUuid())).findAny().get();
            DebugUtils.Assert(mgmtNic!=null, String.format("cannot find management nic for virtual router[uuid:%s, name:%s]", spec.getVmInventory().getUuid(), spec.getVmInventory().getName()));
            vrUuid = spec.getVmInventory().getUuid();
        }

        final FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("virtual-router-%s-get-version", vrUuid));
        chain.setData(flowData);
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "echo";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        String url = vrMgr.buildUrl(mgmtNic.getIp(), VirtualRouterConstant.VR_ECHO_PATH);
                        restf.echo(url, new Completion(trigger) {
                            @Override
                            public void success() {
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        }, TimeUnit.SECONDS.toMillis(1), TimeUnit.SECONDS.toMillis(Long.parseLong(VirtualRouterGlobalConfig.VYOS_ECHO_TIMEOUT.value())));
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "get-version";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        vyosManager.vyosRouterVersionCheck(vrUuid, new Completion(trigger) {
                            @Override
                            public void success() {
                                logger.debug(String.format("virtual router [uuid:%s] version check successfully", vrUuid));
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                logger.warn(String.format("virtual router [uuid:%s] version check failed because %s, need to be reconnect", vrUuid, errorCode.getDetails()));
                                flowData.put(ApplianceVmConstant.Params.isReconnect.toString(), Boolean.TRUE.toString());
                                flowData.put(ApplianceVmConstant.Params.managementNicIp.toString(), mgmtNic.getIp());
                                trigger.next();
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(flowTrigger) {
                    @Override
                    public void handle(Map data) {
                        flowTrigger.next();
                    }
                });

                error(new FlowErrorHandler(flowTrigger) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        flowTrigger.fail(errCode);
                    }
                });
            }
        }).start();
    }
}
