package com.ratelimit.policy.rules;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/rules")
class RuleAdminController {

    private final RuleAdminService service;

    RuleAdminController(RuleAdminService service) {
        this.service = service;
    }

    @GetMapping
    List<RuleView> list() {
        return service.list();
    }

    @GetMapping("/{ruleId}")
    RuleView get(@PathVariable long ruleId) {
        return service.get(ruleId);
    }

    @PostMapping
    ResponseEntity<RuleView> create(@RequestBody RuleRequest request) {
        RuleView created = service.create(request);
        return ResponseEntity.created(URI.create("/admin/rules/" + created.ruleId())).body(created);
    }

    @PutMapping("/{ruleId}")
    RuleView update(@PathVariable long ruleId, @RequestBody RuleRequest request) {
        return service.update(ruleId, request);
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable long ruleId) {
        service.deactivate(ruleId);
    }
}
