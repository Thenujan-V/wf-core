package lk.auradot.wms.engine.runtime;

final class SequentialIds implements IdGenerator {

    private int next = 1;

    @Override
    public String next() {
        return "id-" + next++;
    }
}
