package com.coursewise.shopping.domain;
import jakarta.persistence.*;
/** 模拟支付系统的退款回执，一单取消退款至多一条。 */
@Entity @Table(name="order_refund",uniqueConstraints=@UniqueConstraint(columnNames="order_id"))
public class RefundEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="order_id",nullable=false) private Long orderId;
 @Column(name="pay_channel",nullable=false,length=16) private PayChannel payChannel;
 @Column(name="amount_cents",nullable=false) private long amountCents;
 @Column(name="created_at",nullable=false) private String createdAt;
 @Column(nullable=false) private String status="succeeded";
 public Long getId(){return id;} public Long getOrderId(){return orderId;} public void setOrderId(Long v){orderId=v;}
 public PayChannel getPayChannel(){return payChannel;} public void setPayChannel(PayChannel v){payChannel=v;}
 public long getAmountCents(){return amountCents;} public void setAmountCents(long v){amountCents=v;}
 public String getCreatedAt(){return createdAt;} public void setCreatedAt(String v){createdAt=v;}
 public String getStatus(){return status;}
}
