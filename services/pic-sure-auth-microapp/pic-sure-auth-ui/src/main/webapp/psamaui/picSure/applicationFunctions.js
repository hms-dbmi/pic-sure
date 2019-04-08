define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var applicationFunctions = {
        init: function () {}
    };
    applicationFunctions.fetchApplications = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/application',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                notification.showFailureMessage("Failed to load applications.");
            }
        });
    }.bind(applicationFunctions);

    applicationFunctions.showApplicationDetails = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/application/' + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to load application details.");
            }
        });
    }.bind(applicationFunctions);

    applicationFunctions.createOrUpdateApplication = function (application, requestType, callback) {
        var successMessage = requestType == 'POST' ? 'Application created' : 'Application updated';
        var failureMessage = requestType == 'POST' ? 'Failed to create application' : 'Failed to update application';
        $.ajax({
            url: window.location.origin + settings.basePath + '/application',
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(application),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                notification.showFailureMessage(failureMessage);
            }
        });
    }.bind(applicationFunctions);

    applicationFunctions.deleteApplication = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/application/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage('Application deleted');
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to delete application');
            }
        });
    }.bind(applicationFunctions);

    applicationFunctions.refreshToken = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/application/refreshToken/' + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to refresh token. " + response.message);
            }
        });
    }.bind(applicationFunctions);

	return applicationFunctions;
});