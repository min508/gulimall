package com.atguigu.gulimall.product.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 2 级分类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Catelog2Vo {
    // 一级父分类
    private String catelog1Id;
    // 三级子分类
    private List<Catelog2Vo.Catelog3Vo> catalog3List;
    private String id;
    private String name;

    /**
     * 3 级分类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Catelog3Vo{
        // 父分类，2级分类 id
        private String catelog2Id;
        private String id;
        private String name;
    }
}
