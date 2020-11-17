package org.zstack.test.integration.networkservice.provider.virtualrouter.loadbalancer

import org.zstack.core.db.DatabaseFacade
import org.zstack.header.network.service.NetworkServiceType
import org.zstack.sdk.*
import org.zstack.test.integration.networkservice.provider.NetworkServiceProviderTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.data.SizeUnit
import org.zstack.network.service.eip.EipConstant
import org.zstack.network.service.lb.LoadBalancerConstants
import org.zstack.network.service.portforwarding.PortForwardingConstant
import org.zstack.network.service.virtualrouter.vyos.VyosConstants


class LoadBalancerServerGroupLifeCycleCase extends SubCase{
    DatabaseFacade dbf
    EnvSpec env
    LoadBalancerServerGroupInventory servergroup1
    LoadBalancerServerGroupInventory servergroup2

    @Override
    void setup() {
        useSpring(NetworkServiceProviderTest.springSpec)
    }

    @Override
    void environment() {
        env = env {

            instanceOffering {
                name = "instanceOffering"
                memory = SizeUnit.GIGABYTE.toByte(8)
                cpu = 4
            }

            sftpBackupStorage {
                name = "sftp"
                url = "/sftp"
                username = "root"
                password = "password"
                hostname = "localhost"

                image {
                    name = "image"
                    url = "http://zstack.org/download/test.qcow2"
                }

                image {
                    name = "vr"
                    url = "http://zstack.org/download/vr.qcow2"
                }
            }

            zone {
                name = "zone"
                description = "test"

                cluster {
                    name = "cluster"
                    hypervisorType = "KVM"

                    kvm {
                        name = "kvm"
                        managementIp = "localhost"
                        username = "root"
                        password = "password"
                    }

                    attachPrimaryStorage("local")
                    attachL2Network("l2")
                }

                localPrimaryStorage {
                    name = "local"
                    url = "/local_ps"
                }

                l2NoVlanNetwork {
                    name = "l2"
                    physicalInterface = "eth0"

                    l3Network {
                        name = "l3"

                        service {
                            provider = VyosConstants.VYOS_ROUTER_PROVIDER_TYPE
                            types = [NetworkServiceType.DHCP.toString(),
                                     NetworkServiceType.DNS.toString(),
                                     NetworkServiceType.SNAT.toString(),
                                     PortForwardingConstant.PORTFORWARDING_NETWORK_SERVICE_TYPE,
                                     LoadBalancerConstants.LB_NETWORK_SERVICE_TYPE_STRING,
                                     EipConstant.EIP_NETWORK_SERVICE_TYPE]
                        }

                        ip {
                            startIp = "192.168.100.10"
                            endIp = "192.168.100.100"
                            netmask = "255.255.255.0"
                            gateway = "192.168.100.1"
                        }
                    }

                    l3Network {
                        name = "pubL3"

                        ip {
                            startIp = "11.168.100.10"
                            endIp = "11.168.100.100"
                            netmask = "255.255.255.0"
                            gateway = "11.168.100.1"
                        }
                    }
                }

                attachBackupStorage("sftp")

                virtualRouterOffering {
                    name = "vro"
                    memory = SizeUnit.MEGABYTE.toByte(512)
                    cpu = 2
                    useManagementL3Network("pubL3")
                    usePublicL3Network("pubL3")
                    useImage("vr")
                }

                lb {
                    name = "lb"
                    useVip("pubL3")

                    listener {
                        name = "listener-22"
                        protocol = "tcp"
                        loadBalancerPort = 22
                        instancePort = 22
                    }

                    listener {
                        name = "listener-33"
                        protocol = "tcp"
                        loadBalancerPort = 33
                        instancePort = 33
                    }
                }
            }

            vm {
                name = "vm-1"
                useImage("image")
                useL3Networks("l3")
                useInstanceOffering("instanceOffering")
            }

            vm {
                name = "vm-2"
                useImage("image")
                useL3Networks("l3")
                useInstanceOffering("instanceOffering")
            }
        }
    }

    @Override
    void test() {
        dbf = bean(DatabaseFacade.class)
        env.create {
            createServerGroup()
            TestUpdateLoadBalancerServerGroup()
            TestServerGroupWithBackendServer()
            TestDeleteLoadBalancerServerGroup()
        }
    }

    void createServerGroup(){
        def lb = env.inventoryByName("lb") as LoadBalancerInventory

        servergroup1 = createLoadBalancerServerGroup{
            loadBalancerUuid =  lb.uuid
            name = "lb-group-1"
        }
        assert servergroup1.name == "lb-group-1"
        LoadBalancerServerGroupInventory servergroup = queryLoadBalancerServerGroup { conditions = ["uuid=${servergroup1.uuid}".toString()]}[0]
        assert servergroup.name == "lb-group-1"

        servergroup2 = createLoadBalancerServerGroup {
            loadBalancerUuid =  lb.uuid
            name = "lb-group-2"
        }
        assert servergroup2.name == "lb-group-2"
    }

    void TestUpdateLoadBalancerServerGroup(){
        updateLoadBalancerServerGroup {
            uuid = servergroup1.uuid
            name = "updated name"
        }
        LoadBalancerServerGroupInventory servergroup = queryLoadBalancerServerGroup { conditions = ["uuid=${servergroup1.uuid}".toString()]}[0]
        assert servergroup.name == "updated name"
    }

    void TestServerGroupWithBackendServer(){
        def l3 = env.inventoryByName("l3") as L3NetworkInventory
        def vm = env.inventoryByName("vm-1") as VmInstanceInventory

        /* shared load balancer can not add server group */
        expect(AssertionError.class) {
            addBackendServerToServerGroup {
                vmNics = [['uuid':vm.vmNics.find{ nic -> nic.l3NetworkUuid == l3.uuid }.uuid,'weight':'20']]
                servers  = [['ipAddress':"20.20.20.1",'weight':'30']]
                serverGroupUuid = servergroup1.uuid
            }
        }
        addBackendServerToServerGroup {
            vmNics = [['uuid':vm.vmNics.find{ nic -> nic.l3NetworkUuid == l3.uuid }.uuid,'weight':'20']]
            serverGroupUuid = servergroup1.uuid
        }
        LoadBalancerServerGroupInventory servergroup = queryLoadBalancerServerGroup{ conditions = ["uuid=${servergroup1.uuid}".toString()]}[0]
        assert servergroup.vmNicRefs.size() == 1
        assert servergroup.vmNicRefs.get(0).vmNicUuid == vm.vmNics.find { nic -> nic.l3NetworkUuid == l3.uuid }.uuid
        assert servergroup.vmNicRefs[0].weight == 20

        /* shared load balancer can not change server ip  */
        expect(AssertionError.class) {
            changeLoadBalancerBackendServer {
                vmNics = [['uuid': vm.vmNics.find { nic -> nic.l3NetworkUuid == l3.uuid }.uuid, 'weight': '40']]
                servers = [['ipAddress': "20.20.20.1", 'weight': '50']]
                serverGroupUuid = servergroup1.uuid
            }
        }

        changeLoadBalancerBackendServer {
            vmNics = [['uuid': vm.vmNics.find { nic -> nic.l3NetworkUuid == l3.uuid }.uuid, 'weight': '40']]
            serverGroupUuid = servergroup1.uuid
        }

        servergroup = queryLoadBalancerServerGroup{ conditions = ["uuid=${servergroup1.uuid}".toString()]}[0]
        assert servergroup.vmNicRefs.size() == 1
        assert servergroup.vmNicRefs[0].weight == 40

        removeBackendServerFromServerGroup{
            vmNicUuids = [vm.vmNics.find{ nic -> nic.l3NetworkUuid == l3.uuid }.uuid]
            serverGroupUuid = servergroup1.uuid
        }
        servergroup = queryLoadBalancerServerGroup{ conditions = ["uuid=${servergroup1.uuid}".toString()]}[0]
        assert servergroup.vmNicRefs.isEmpty()
    }

    void TestDeleteLoadBalancerServerGroup(){
        deleteLoadBalancerServerGroup{
            uuid = servergroup1.uuid
        }
    }


    @Override
    void clean() {
        env.delete()
    }


}