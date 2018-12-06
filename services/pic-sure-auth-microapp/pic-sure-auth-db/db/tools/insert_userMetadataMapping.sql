set @uuid=0x749988c96ae44db2b08063b72bbe4160;
set @authMetadata='$.email';
set @connection_uuid='{the uuid from corresponding connection}';
set @generalMetadata='$.email';

begin;
insert into `userMetadataMapping` values (@uuid, @authMetadata, @connection_uuid, @generalMetadata);
commit;