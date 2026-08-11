package com.tanrunn.buildshop.core;

/** 可原子替换的目录存储（数据包重载后整体换新）。 */
public final class CatalogStore {

    private volatile ProductCatalog catalog = ProductCatalog.empty();

    public ProductCatalog catalog() {
        return catalog;
    }

    public void set(ProductCatalog catalog) {
        if (catalog != null) {
            this.catalog = catalog;
        }
    }

    public void clear() {
        this.catalog = ProductCatalog.empty();
    }
}
