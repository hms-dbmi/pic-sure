define(["header/header", "jquery", "user/userManagement"],
		function(header, $, userManagement){
	console.log("in startup");
   	localStorage.setItem('id_token', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZHxsZGFwLWNvbm5lY3Rvcnw5MGQzMGZiZS05ZGFjLTQzYmMtYWZjNC0xOGJiN2I4ZjVhOTciLCJpc3MiOiJiYXIiLCJleHAiOjE1ODI0NzAwNzgsImlhdCI6MTUzOTI3MDA3OCwianRpIjoiRm9vIiwiZW1haWwiOiJhZHxsZGFwLWNvbm5lY3Rvcnw5MGQzMGZiZS05ZGFjLTQzYmMtYWZjNC0xOGJiN2I4ZjVhOTcifQ.IqrulTPQJxzruhSOmj6niyRAjfA43VSSnwH6IlwlHSA');

   	$.ajaxSetup({"headers":{"Authorization":"Bearer " + localStorage.id_token}});

	var header = header.View;
	header.render();
	$('#header-content').append(header.$el);

	var userMngmt = new userManagement.View({model: new userManagement.Model()});
    userMngmt.render();
	$('#user-div').append(userMngmt.$el);
});