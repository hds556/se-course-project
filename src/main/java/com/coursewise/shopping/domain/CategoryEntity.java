package com.coursewise.shopping.domain;

import jakarta.persistence.*;

/** parentId 为空是一级，否则必须指向一级类别；归属创建后固定。 */
@Entity
@Table(name = "category", indexes = @Index(name = "idx_category_parent", columnList = "parent_id"))
public class CategoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;
    @Column(name = "parent_id")
    private Long parentId;
    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long value) { parentId = value; }
}
