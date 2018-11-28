// Util functions get data to/from picsure
define(["util/notification", "text!picsure/connections.json", "util/notification"],
		function(notification, connectionsJSON, notification){
    var picsureFunctions = {
        init: function () {}
    };

    picsureFunctions.getConnection = function (connectionUuid, callback) {
        $.ajax({
            url: window.location.origin + "/auth/connection/" + (connectionUuid ? connectionUuid : ""),
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                console.log("Failed to get connections from server.");
                return connectionsJSON;
            }
        });
    }.bind(picsureFunctions);
    picsureFunctions.createOrUpdateConnection = function (connections, requestType, callback) {
        var successMessage = "Successfully added a connection.";
        var failureMessage = "Failed to add a connection.";
        $.ajax({
            url: window.location.origin + "/auth/connection",
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(connections),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                notification.showSuccessMessage(failureMessage);
                console.log(response.message);

            }
        });
    }.bind(picsureFunctions);
    picsureFunctions.deleteConnection = function (uuid, callback) {
        var successMessage = "Successfully deleted connection.";
        var failureMessage = "Failed to delete connection.";
        $.ajax({
            url: window.location.origin + '/auth/connection/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                notification.showFailureMessage(failureMessage);
            }
        });
    }.bind(picsureFunctions);

    picsureFunctions.getLatestTOS = function (callback) {
        $.ajax({
            url: window.location.origin + "/auth/tos/latest",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to load Terms of Service.");
            }
        });
    }.bind(picsureFunctions);

    picsureFunctions.acceptTOS = function (callback) {
        $.ajax({
            url: window.location.origin + "/auth/tos/accept",
            type: 'POST',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to accept Terms of Service.");
            }
        });
    }.bind(picsureFunctions);

    return picsureFunctions;
});