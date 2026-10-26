package lk.auradot.wms.document.type;

import lk.auradot.wms.document.forms.FormRef;

/** A kind of document; its metadata fields are the published form it names (null = no metadata). */
public record DocumentType(String id, String name, FormRef form) {
}
