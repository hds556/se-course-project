package com.coursewise.shopping.domain;
import jakarta.persistence.*;
/** SRS V5.0 §6.4：供地址管理域和下单域共同读取的收货地址。 */
@Entity @Table(name="address", indexes=@Index(name="idx_address_customer",columnList="customer_id"))
public class AddressEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="customer_id",nullable=false) private Long customerId;
 @Column(name="address_text",nullable=false,length=200) private String addressText;
 @Column(name="is_default",nullable=false) private boolean defaultAddress;
 @Column(name="created_at",nullable=false) private String createdAt;
 public Long getId(){return id;} public Long getCustomerId(){return customerId;} public void setCustomerId(Long v){customerId=v;}
 public String getAddressText(){return addressText;} public void setAddressText(String v){addressText=v;}
 public boolean isDefaultAddress(){return defaultAddress;} public void setDefaultAddress(boolean v){defaultAddress=v;}
 public String getCreatedAt(){return createdAt;} public void setCreatedAt(String v){createdAt=v;}
}
