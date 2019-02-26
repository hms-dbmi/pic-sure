set @uuid=0x2a3966ea7d024041975d784322b48342;
set @label='Google';
set @id='google-oauth2';
set @subprefix='google-oauth2|';
set @requiredFields='[{\"label\":\"Email\", \"id\":\"email\"}]';

begin;
insert into `connection` values (@uuid, @lable, @id, @subprefix, @requiredFields);
commit;