package lk.auradot.wms.workflow.job;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
