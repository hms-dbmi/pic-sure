set @user_uuid='91124ef7df204412b4f399565c547bd9';
set @user_subject='{{change_me}}';
set @connection_uuid='{{change_me}}';
set @matched=true;

create or replace view management_view as
select
role.uuid, role.name as role_name, privilege.name as privilege_name
from
role, role_privilege, privilege
where
role.uuid = role_privilege.role_id
and privilege.uuid = role_privilege.privilege_id;

begin;
insert into user (`uuid`,`subject`,`connectionId`,`matched`) values (unhex(@user_uuid), @user_subject, @connection_uuid, true);
insert into user_role (`user_id`,`role_id`) values (unhex(@user_uuid),
(select min(uuid) from management_view where privilege_name like 'ROLE_SYSTEM')) ;
commit;