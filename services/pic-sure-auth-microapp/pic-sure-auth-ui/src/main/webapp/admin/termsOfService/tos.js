define(["backbone","handlebars", "text!termsOfService/tos.hbs", "picSure/tosFunctions", "util/notification"],
    function(BB, HBS, template, tosFunctions, notification){
        var tosModel = BB.Model.extend({
        });

        var tosView = BB.View.extend({
            template : HBS.compile(template),
            events : {
                "click .accept-tos-button":   "acceptTOS",
            },
            acceptTOS: function () {
                tosFunctions.acceptTOS(function(){
                    if (sessionStorage.redirection_url) {
                        window.location = sessionStorage.redirection_url;
                    }
                    else {
                        history.pushState({}, "", "userManagement");
                    }
                })
            },
            render : function(){
                tosFunctions.getLatestTOS(function (content) {
                    this.model.set("content", content);
                    this.$el.html(this.template({content: content}));
                }.bind(this));
            }
        });

        return {
            View : tosView,
            Model: tosModel
        };
});