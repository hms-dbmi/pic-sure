define(["Noty", "handlebars", "text!options/confirmationDialog.hbs",],
        function(Noty, HBS, confirmationDialog){
    var notification = {
        init: function () {

        }
    };
    notification.showSuccessMessage = function (message) {
        new Noty({
            type: "success",
            text: message,
            timeout: 3000
        }).show();
    }.bind(notification);

    notification.showFailureMessage = function (message) {
        new Noty({
            type: "error",
            text: message,
            timeout: 3000
        }).show();
    }.bind(notification);

    notification.showConfirmationDialog = function (callback) {
        var n = new Noty({
            text: 'Do you want to continue?',
            layout: 'topCenter',
            buttons: [
                Noty.button('YES', 'btn btn-info', function () {
                    n.close();
                    callback();
                }),
                Noty.button('NO', 'btn btn-danger btn-right', function () {
                    n.close();
                })
            ]
        }).show();

    }.bind(notification);

	return notification;
});