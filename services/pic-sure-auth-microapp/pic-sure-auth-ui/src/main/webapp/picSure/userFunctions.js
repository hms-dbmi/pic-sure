define([],
		function(){
    var userFunctions = {
        init: function () {

        }
    };
    userFunctions.fetchUsers = function (object, callback) {
        $.ajax({
            url: window.location.origin + "/picsure/user",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                alert("fetchUsers failed");
            }
        });
    }.bind(userFunctions);

    userFunctions.showUserDetails = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + "/picsure/user/" + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                alert("failed");
            }
        });
    }.bind(userFunctions);

    userFunctions.createOrUpdateUser = function (user, requestType, callback) {
        $.ajax({
            url: window.location.origin + "/picsure/user",
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(user),
            success: function(response){
                callback(response);
            },
            error: function(response){
                alert("failed");
            }
        });
    }.bind(userFunctions);

    userFunctions.deleteUser = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + "/picsure/user/" + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                alert("failed");
            }
        });
    }.bind(userFunctions);

    userFunctions.getAvailableRoles = function (callback) {
        $.ajax({
            url: window.location.origin + "/picsure/user/availableRoles",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                console.log(response);
                callback(response);
            },
            error: function(response){
                alert("failed");
            }
        });
    }.bind(userFunctions);

	return userFunctions;
});