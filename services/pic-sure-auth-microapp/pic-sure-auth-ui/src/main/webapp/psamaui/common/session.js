
define(["jquery", "underscore", "common/styles"], function($, _){
	var storedSession = JSON.parse(
			sessionStorage.getItem("session"));
	
	var session = storedSession ? storedSession : {
		username : null,
		permissions : [],
		privileges : [],
		email : null
	};
	
	var configureAjax = function(callback){
		$.ajaxSetup({
			headers: {"Authorization": "Bearer " + session.token},
			statusCode: {
				401: function(){
                    callback();
				},
				403: function(){
                    history.pushState({}, "", "/psamaui/not_authorized");
				}
			}
		});
	};
	
	return {
		username : session.username,
		may : function(permission){
			return _.contains(permission, session.permissions);
		},
		authenticated : function(userId, token, username, permissions, acceptedTOS, callback){
			session.userId = userId;
			session.token = token;
			session.username = username;
			session.permissions = permissions;
			session.acceptedTOS = acceptedTOS;
			sessionStorage.setItem("session", JSON.stringify(session));
			configureAjax(callback);
		},
		isValid : function(callback){
			if(session.username){
				configureAjax(callback);
				return session.username;
			}else{
				return false;
			}
		},
		token: function(){
			return JSON.parse(sessionStorage.session).token;
		},
		setToken: function(token){
            session.token = token;
            sessionStorage.setItem("session", JSON.stringify(session));
		},
		username : function(){
			return JSON.parse(sessionStorage.session).username;
		},
        email : function(){
            return JSON.parse(sessionStorage.session).email;
        },
		userId : function(){
			return JSON.parse(sessionStorage.session).userId;
		},
		// userMode : function(){
		// 	return JSON.parse(sessionStorage.session).currentUserMode;
		// },
		acceptedTOS : function(){
			return JSON.parse(sessionStorage.session).acceptedTOS;
		},
		privileges: function(){
			return JSON.parse(sessionStorage.session).privileges;
		},
		activity : _.throttle(function(activity){
			if(typeof activity !== "string"){
				activity = window.location.href;
			}
            /**
			 * /interaction end-point cannot be found. Do we still need to call it?
			 */
			// $.ajax({
			// 	data: JSON.stringify({
			// 		description : activity
			// 	}),
			// 	url: "/rest/interaction",
			// 	type: 'POST',
			// 	dataType: "json",
			// 	contentType: "application/json"
			// });
		}, 10000),
		setAcceptedTOS : function() {
			session.acceptedTOS = true;
            sessionStorage.setItem("session", JSON.stringify(session));
		}
	}
});