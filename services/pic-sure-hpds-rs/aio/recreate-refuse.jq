def normal: if . == null then "" elif . == [] or . == {} then "" else . end;
def differs($a; $b): ($a | normal) != ($b | normal);

.[0] as $c
| $img[0] as $i
| [
    (if differs($c.Config.Entrypoint; $i.Config.Entrypoint) then "entrypoint differs from the image" else empty end),
    (if differs($c.Config.Cmd; $i.Config.Cmd) then "command differs from the image" else empty end),
    (if differs($c.Config.User; $i.Config.User) then "user differs from the image" else empty end),
    (if differs($c.Config.WorkingDir; $i.Config.WorkingDir) then "working directory differs from the image" else empty end),
    (if differs($c.Config.Healthcheck; $i.Config.Healthcheck) then "healthcheck differs from the image" else empty end),
    (if $c.HostConfig.Privileged then "privileged" else empty end),
    (if ($c.HostConfig.CapAdd // []) != [] then "added capabilities" else empty end),
    (if ($c.HostConfig.CapDrop // []) != [] then "dropped capabilities" else empty end),
    (if ($c.HostConfig.Devices // []) != [] then "devices" else empty end),
    (if ($c.HostConfig.Tmpfs // {}) != {} then "tmpfs mounts" else empty end),
    (if ($c.HostConfig.ExtraHosts // []) != [] then "extra hosts" else empty end),
    (if ($c.HostConfig.Dns // []) != [] then "custom dns" else empty end),
    (if ($c.HostConfig.SecurityOpt // []) != [] then "security options" else empty end),
    (if ($c.HostConfig.Memory // 0) != 0 then "memory limit" else empty end),
    (if ($c.HostConfig.NanoCpus // 0) != 0 then "cpu limit" else empty end),
    (if ($c.HostConfig.PidMode // "") != "" then "pid mode" else empty end),
    (if ($c.HostConfig.NetworkMode // "") | startswith("container:") then "shares another container's network" else empty end),
    (if ($c.HostConfig.LogConfig.Type // "json-file") != "json-file" or ($c.HostConfig.LogConfig.Config // {}) != {} then "non-default log configuration" else empty end),
    (if ($c.HostConfig.Init // false) then "init process" else empty end),
    (if ($c.HostConfig.Ulimits // []) != [] then "ulimits" else empty end),
    (if ($c.HostConfig.ShmSize // 67108864) != 67108864 then "shm size" else empty end),
    (if ($c.HostConfig.ReadonlyRootfs // false) then "read-only root filesystem" else empty end),
    (if ($c.HostConfig.Links // []) != [] then "links" else empty end),
    (if ($c.NetworkSettings.Networks | to_entries | map(.value.IPAMConfig // {} | select((.IPv4Address // "") != "" or (.IPv6Address // "") != "")) | length) > 0 then "static ip address" else empty end),
    (if ($c.Config.MacAddress // "") != "" then "static mac address" else empty end),
    (if ($c.Config.Hostname // "") != ($c.Id[0:12]) then "custom hostname" else empty end),
    (if ($c.Config.Domainname // "") != "" then "custom domain name" else empty end),
    (if ($c.HostConfig.GroupAdd // []) != [] then "supplementary groups" else empty end),
    (if ($c.HostConfig.Sysctls // {}) != {} then "sysctls" else empty end),
    (if differs($c.Config.StopSignal; $i.Config.StopSignal) then "stop signal differs from the image" else empty end),
    (if $c.Config.StopTimeout != null then "stop timeout" else empty end),
    (if ($c.HostConfig.MemoryReservation // 0) != 0 then "memory reservation" else empty end),
    (if ($c.HostConfig.CpuShares // 0) != 0 then "cpu shares" else empty end),
    (if ($c.HostConfig.PidsLimit // 0) > 0 then "pids limit" else empty end),
    (if ($c.Config.Env | map(select(test("\n"))) | length) > 0 then "multi-line environment value" else empty end)
  ]
| .[]
