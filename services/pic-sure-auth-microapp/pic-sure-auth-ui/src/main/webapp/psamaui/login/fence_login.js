define(['common/session', 'picSure/settings', 'common/searchParser', 'jquery', 'handlebars', 'text!login/fence_login.hbs', 'text!login/not_authorized.hbs', 'overrides/login', 'util/notification', 'footer/footer'],
    function(session, settings, parseQueryString, $, HBS, loginTemplate, notAuthorizedTemplate, overrides, notification, footer){
        var loginTemplate = HBS.compile(loginTemplate);

        var login = {
            showLoginPage : function(){

                // Check if the `code` parameter is set in the URL, as it would be, when
                // FENCE redirects back after authentication.
                var queryString = window.location.search.substring(1);
                var params = {}, queries, temp, i, l;
                // Split into key/value pairs
                queries = queryString.split("&");
                // Convert the array of strings into an object
                for ( i = 0, l = queries.length; i < l; i++ ) {
                    temp = queries[i].split('=');
                    params[temp[0]] = temp[1];
                }
                var code = params['code'];
                if (code) {
                    $('#main-content').html('DataStage authentication is successful. Processing UserProfile information...');

                    $.ajax({
                        url: '/psama/authentication',
                        type: 'post',
                        data: JSON.stringify({
                           code: code
                        }),
                        contentType: 'application/json',
                        success: function(data){
                            console.log('showLoginPage() psama fence-authentication is successful.');
                            console.log(data);

                            // If back-end response is success, we will get a PSAMA JWT token back, and some
                            // other information. We will set the session variables for the user with our own
                            // internal expiry, and other claims.
                            session.authenticated(
                                data.userId,
                                data.token,
                                data.email,
                                data.permissions,
                                data.acceptedTOS,
                                this.handleNotAuthorizedResponse
                            );

                            if (data.acceptedTOS !== 'true'){
                                history.pushState({}, "", "/psamaui/tos");
                            } else {
                                window.location = '/picsureui';
                            }

                        }.bind(this),
                        error: function(data){
                            notification.showFailureMessage("Failed to authenticate with provider. Try again or contact administrator if error persists.")
                            history.pushState({}, "", sessionStorage.not_authorized_url? sessionStorage.not_authorized_url : "/psamaui/not_authorized?redirection_url=/picsureui");
                        }
                    });
                    return null;
                } else {
                    console.log("FENCE-showLoginPage() no code in query string, redirecting to FENCE");

                    // Show the fence_login template, with the generated fenceLoginURL
                    $('#main-content').html(loginTemplate({
                        fenceURL : settings.idp_provider_uri + "/user/oauth2/authorize"+
                            "?response_type=code"+
                            "&scope=user+openid"+
                            "&client_id=" + settings.fence_client_id +
                            "&redirect_uri="+settings.fence_redirect_url
                    }));
                    
                    //also need to show footer on login page
                    var footerView = footer.View;
                    footerView.render();
                    $('#footer-content').append(footerView.$el);
                    
                    return null;
                }
                console.log("FENCE-showLoginPage() finished");
            },
            handleNotAuthorizedResponse : function () {
                console.log("FENCE-handleNotAuthorizedResponse() starting....");

                if (JSON.parse(sessionStorage.session).token) {
                    if (sessionStorage.not_authorized_url)
                        window.location = sessionStorage.not_authorized_url;
                    else
                        window.location = "/psamaui/not_authorized" + window.location.search;
                } else {
                  // Do nothing if there is not token, and authorization failed.
                }
            },
            displayNotAuthorized : function () {
                console.log("FENCE-displayNotAuthorized() starting...");

                if (overrides.displayNotAuthorized)
                    overrides.displayNotAuthorized()
                else
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)({helpLink:settings.helpLink}));
                
                //also need to show footer everywhere
                var footerView = footer.View;
                footerView.render();
                $('#footer-content').append(footerView.$el);
            }
        };
        return login;
    });
