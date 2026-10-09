def restart_flag:
  .HostConfig.RestartPolicy as $p
  | if ($p.Name == "" or $p.Name == "no") then "--restart=no"
    elif $p.Name == "on-failure" and $p.MaximumRetryCount > 0 then "--restart=on-failure:\($p.MaximumRetryCount)"
    else "--restart=\($p.Name)" end;

.[0]
| restart_flag,
  (.Mounts[]?
    | "-v",
      ((if .Type == "bind" then .Source elif .Type == "volume" then .Name else error("unsupported mount type \(.Type)") end)
        + ":" + .Destination + (if .RW then "" else ":ro" end))),
  (.HostConfig.PortBindings // {} | to_entries[] | .key as $port
    | .value[]?
    | "-p", ((if (.HostIp // "") != "" then .HostIp + ":" else "" end) + .HostPort + ":" + $port)),
  (.Config.Labels // {} | to_entries[]
    | select((($img[0].Config.Labels // {})[.key]) != .value)
    | "--label", "\(.key)=\(.value)")
