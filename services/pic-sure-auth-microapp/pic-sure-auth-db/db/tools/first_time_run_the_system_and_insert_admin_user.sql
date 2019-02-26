#configuration for user table
#for quick usage, please use a google user
#otherwise you need to modify connection configuration as well
set @user_uuid='6dc35d47c7c64ca7beb42edac5184e10';
set @user_email="change_me_to_your_email";
set @matched=false;

#configuration for connection table
set @connection_uuid=0x6dc35d47c7c64ca7beb42edac5184e10;
set @label='Google';
set @id='google-oauth2';
set @subprefix='google-oauth2|';
set @requiredFields='[{\"label\":\"Email\", \"id\":\"email\"}]';

#configuration for userMetadataMapping table
set @userMetadataMapping_uuid=0xed5c801a73ef4a7281de07363bb5cdba;
set @authMetadata='$.email';
set @generalMetadata='$.email';

begin;
#### insert into connection table
insert into `connection` values (@connection_uuid, @label, @id, @subprefix, @requiredFields);

#### insert into user table
create or replace view management_view as
select
role.uuid, role.name as role_name, privilege.name as privilege_name
from
role, role_privilege, privilege
where
role.uuid = role_privilege.role_id
and privilege.uuid = role_privilege.privilege_id;

insert into user (`uuid`,`general_metadata`,`connectionId`,`matched`) values (unhex(@user_uuid), CONCAT("{\"email\":\"", @user_email, "\"}"), @connection_uuid, @matched);
insert into user_role (`user_id`,`role_id`) values (unhex(@user_uuid),
(select min(uuid) from management_view where privilege_name like 'ROLE_SYSTEM')) ;

#### insert into userMetadataMapping
insert into `userMetadataMapping` values (@userMetadataMapping_uuid, @authMetadata, @connection_uuid, @generalMetadata);
commit;

