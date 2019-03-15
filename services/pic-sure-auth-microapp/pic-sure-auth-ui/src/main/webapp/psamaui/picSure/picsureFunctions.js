// Util functions get data to/from picsure
define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var picsureFunctions = {
        init: function () {}
    };

    var connections = undefined;
    // forceAjax flag is to decide where get connections from API or local storage
    picsureFunctions.getConnection = function (connectionUuid, forceAjax, callback) {
        if(!forceAjax && connections) {
            if (connectionUuid)
                callback(_.findWhere(connections, {uuid: connectionUuid}));
            else
                callback(connections);
        }else{
            $.ajax({
                url: window.location.origin + settings.basePath + '/connection/' + (connectionUuid ? connectionUuid : ''),
                type: 'GET',
                contentType: 'application/json',
                success: function(response){
                    connections = response;
                    callback(response);
                },
                error: function(response){
                    notification.showFailureMessage("Failed to get connection(s) from server.");
                    console.log(response);
                }
            });
        }
    }.bind(picsureFunctions);
    picsureFunctions.createOrUpdateConnection = function (connections, requestType, callback) {
        var successMessage = 'Successfully added a connection.';
        var failureMessage = 'Failed to add a connection.';
        $.ajax({
            url: window.location.origin + settings.basePath + '/connection',
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
        var successMessage = 'Successfully deleted connection.';
        var failureMessage = 'Failed to delete connection.';
        $.ajax({
            url: window.location.origin + settings.basePath + '/connection/' + uuid,
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
            url: window.location.origin + settings.basePath + '/tos/latest',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to load Terms of Service.');
            }
        });
    }.bind(picsureFunctions);

    picsureFunctions.acceptTOS = function (callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/tos/accept',
            type: 'POST',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to accept Terms of Service.');
            }
        });
    }.bind(picsureFunctions);

    return picsureFunctions;
});