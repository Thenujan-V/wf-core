package lk.auradot.wms.notification.directory;

import java.util.List;
import java.util.Optional;

/** notification-service's view of identity-service. Every method throws {@link DirectoryUnavailableException} on an outage. */
public interface Directory {

    /** Empty when nobody has this id. */
    Optional<Person> person(String userId);

    List<Person> groupMembers(String groupId);

    List<Person> roleMembers(String role);
}
