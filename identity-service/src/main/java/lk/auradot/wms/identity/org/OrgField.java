package lk.auradot.wms.identity.org;

public enum OrgField {
    MANAGER("manager_id", "managerId"),
    DEPARTMENT("department", "department"),
    POSITION("position_title", "position");

    private final String column;
    private final String apiName;

    OrgField(String column, String apiName) {
        this.column = column;
        this.apiName = apiName;
    }

    public String column() {
        return column;
    }

    public String apiName() {
        return apiName;
    }
}
