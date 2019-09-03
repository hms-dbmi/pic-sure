define(['jquery'], function($){
	if(sessionStorage.getItem("session")){
		console.log("success");
	}else{
		if(window.location.search){
			// This logic should be done on the backend.
			var code = window.location.search.slice(6);
			$.ajax({
				url: "https://staging.datastage.io/user/oauth2/token",
				type: 'POST',
				headers: {
					"Authorization" : "Basic " + btoa(client_id + ":" + client_secret)
				},
				data: {
					grant_type: "authorization_code",
					code: code,
					redirect_uri: "https://datastage-i2b2-transmart-stage.aws.dbmi.hms.harvard.edu/psamaui/login/"
				},
				success: function(tokens){
					// This logic should be done on the backend. 
					console.log(tokens);
					var id_token = tokens.id_token;
					var access_token = tokens.access_token;
					$('body').append("<pre>"+JSON.stringify(JSON.parse(atob(id_token.split('.')[1])),null, 2)+"</pre>");
					$.ajax({
						url: "https://staging.datastage.io/user/user",
						type: 'GET',
						headers: {
							"Authorization" : "Bearer " + access_token
						},
						success: function(userProfile){
							// The user's roles will come from this reponse.
							$('body').append("<pre>"+JSON.stringify(userProfile)+"</pre>");
						}
					});
				}
			});
		}else{
			//this is the redirect to login that should happen if the user has not logged in
			window.location = "https://staging.datastage.io/user/oauth2/authorize" + "?response_type=code&scope=user+openid&client_id=" + client_id + "&redirect_uri=https://datastage-i2b2-transmart-stage.aws.dbmi.hms.harvard.edu/psamaui/login/"
		}
	}
});
