resource "aws_lb" "product" {
  count              = var.load_test_profile == "product-scaleout" ? 1 : 0
  name               = "${var.project}-product"
  internal           = true
  load_balancer_type = "network"
  subnets            = [aws_subnet.private.id]

  tags = { Name = "${var.project}-product" }
}

resource "aws_lb_target_group" "product" {
  count       = var.load_test_profile == "product-scaleout" ? 1 : 0
  name        = "${var.project}-product"
  port        = 8084
  protocol    = "TCP"
  target_type = "instance"
  vpc_id      = aws_vpc.main.id

  health_check {
    protocol = "HTTP"
    path     = "/actuator/health"
    matcher  = "200"
  }
}

resource "aws_lb_listener" "product" {
  count             = var.load_test_profile == "product-scaleout" ? 1 : 0
  load_balancer_arn = aws_lb.product[0].arn
  port              = 8084
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.product[0].arn
  }
}

resource "aws_lb_target_group_attachment" "product" {
  for_each         = var.load_test_profile == "product-scaleout" ? toset(["product-a", "product-b", "product-c", "product-d"]) : toset([])
  target_group_arn = aws_lb_target_group.product[0].arn
  target_id        = aws_instance.node[each.key].id
  port             = 8084
}
