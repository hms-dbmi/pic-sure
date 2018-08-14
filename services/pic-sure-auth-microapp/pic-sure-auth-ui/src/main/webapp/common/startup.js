define(["header/header", "jquery", "user/userManagement"],
		function(header, $, userManagement){
	console.log("in startup");
   	localStorage.setItem('id_token', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGVrc2FuZC5uaWtpdGluQGNoaWxkcmVucy5oYXJ2YXJkLmVkdSIsImlzcyI6ImJhciIsImV4cCI6MTU0MTE5Mjc1MSwiaWF0IjoxNTMyNTUyNzUxLCJqdGkiOiJGb28iLCJlbWFpbCI6ImFsZWtzYW5kLm5pa2l0aW5AY2hpbGRyZW5zLmhhcnZhcmQuZWR1In0.JjsAojQr-k8oxBC5u2jBtM03ljpWG0GejrNu81GGk-c');

   	$.ajaxSetup({"headers":{"Authorization":"Bearer " + localStorage.id_token}});

	var header = header.View;
	header.render();
	$('#header-content').append(header.$el);

	var userMngmt = new userManagement.View({model: new userManagement.Model()});
    userMngmt.render();
	$('#user-div').append(userMngmt.$el);
});