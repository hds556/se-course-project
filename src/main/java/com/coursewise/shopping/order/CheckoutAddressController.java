package com.coursewise.shopping.order;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.Map;
@RestController
public class CheckoutAddressController {
 private final CheckoutAddressService addresses;
 public CheckoutAddressController(CheckoutAddressService a){addresses=a;}
 @GetMapping("/api/checkout/addresses") public ObjectNode list(@CookieValue(name="customer_session",required=false) String session){return addresses.list(session);}
 @PostMapping("/api/checkout/addresses") @ResponseStatus(HttpStatus.CREATED)
 public ObjectNode add(@CookieValue(name="customer_session",required=false) String session,@RequestBody Map<String,Object> body){return addresses.add(session,body.get("addressText") instanceof String s?s:null);}
}
