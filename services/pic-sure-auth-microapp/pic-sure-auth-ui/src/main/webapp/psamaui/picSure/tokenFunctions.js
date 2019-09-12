define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var tokenFunctions = {
        init: function () {}
    };
    tokenFunctions.refreshToken = function (object, callback) {
        var failureMessage = "Failed to refresh tokens.";
        $.ajax({
            url: window.location.origin + settings.basePath + '/token/refresh',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                handleAjaxError(response, failureMessage);
            }
        });
    }.bind(tokenFunctions);

    var handleAjaxError = function (response, message) {
        if (response.status !== 401) {
            notification.showFailureMessage(message);
        }
    }.bind(tokenFunctions);

	return tokenFunctions;
});