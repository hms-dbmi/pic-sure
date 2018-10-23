define(["jquery", "underscore"], function($, _){
	var storedSession = JSON.parse(
			sessionStorage.getItem("session"));
	
	var session = storedSession ? storedSession : {
		username : null,
		permissions : []
	};
	
	var configureAjax = function(){
		$.ajaxSetup({
			headers: {"Authorization": "Bearer " + session.token},
			statusCode: {
				401: function(){
					window.location = "/logout";
				}
			}
		});
	};
	
	return {
		username : session.username,
		may : function(permission){
			return _.contains(permission, session.permissions);
		},
		authenticated : function(userId, token, username, permissions){
			session.userId = userId;
			session.token = token;
			session.username = username;
			session.permissions = permissions;
			sessionStorage.setItem("session", JSON.stringify(session));
			configureAjax();
		},
		isValid : function(){
			if(session.username){
				configureAjax();
				return session.username;
			}else{
				return false;
			}
		},
		userEmail : function(){
			return JSON.parse(sessionStorage.session).username;
		},
		userId : function(){
			return JSON.parse(sessionStorage.session).userId;
		},
		userMode : function(){
			return JSON.parse(sessionStorage.session).currentUserMode;
		},
		activity : _.throttle(function(activity){
			if(typeof activity !== "string"){
				activity = window.location.href;
			}
			$.ajax({
				data: JSON.stringify({
					description : activity
				}),
				url: "/rest/interaction",
				type: 'POST',
				dataType: "json",
				contentType: "application/json"
			});
		}, 10000)
	}
});