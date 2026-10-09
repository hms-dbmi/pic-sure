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
    (if ($c.HostConfig.LogConfig.Type // "json-file") != "json-file" then "custom log driver" else empty end),
    (if ($c.Config.Env | map(select(test("\n"))) | length) > 0 then "multi-line environment value" else empty end)
  ]
| .[]
