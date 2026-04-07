package io.github.skyleew.relationmapping.domain;

import lombok.Data;

@Data
public class RelationModelStructure  {

    private TableStructure selfTableStructure;

    private TableStructure relationTableStructure;

    private boolean isCount;

}

