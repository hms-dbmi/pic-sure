package edu.harvard.hms.dbmi.avillach.conventions.entityfixtures.rest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.conventions.entityfixtures.entity.Role;
import edu.harvard.hms.dbmi.avillach.conventions.entityfixtures.entity.User;

/** Handlers binding entities in each shape the rule has to see through, next to shapes it must accept. */
@RestController
public class EntityBindingController {

    @PostMapping("/bare")
    public void bare(@RequestBody User user) {}

    @PostMapping("/list")
    public void list(@RequestBody List<Role> roles) {}

    @PostMapping("/nested")
    public void nested(@RequestBody Map<String, List<User>> usersByGroup) {}

    @PostMapping("/array")
    public void array(@RequestBody User[] users) {}

    @PostMapping("/optional")
    public void optional(@RequestBody Optional<User> user) {}

    @PostMapping("/wildcard")
    public void wildcard(@RequestBody List<? extends Role> roles) {}

    @PutMapping("/both")
    public void both(@RequestBody Map<User, Role> assignments) {}

    @PostMapping("/bounded")
    public <T extends User> void bounded(@RequestBody T user) {}

    @PostMapping("/recursive")
    public <T extends Comparable<T>> void recursive(@RequestBody T value) {}

    @PostMapping("/generic-array")
    public void genericArray(@RequestBody List<Role>[] roleGroups) {}

    @GetMapping("/model")
    public void model(String query, @ModelAttribute Role role) {}

    @PostMapping("/display")
    public List<User.UserForDisplay> display(@RequestBody List<User.UserForDisplay> users) {
        return users;
    }

    @PostMapping("/record")
    public void record(@RequestBody RoleRequest request) {}

    public void unmapped(User user) {}
}
